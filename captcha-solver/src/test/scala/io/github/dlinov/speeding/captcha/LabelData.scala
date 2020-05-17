package io.github.dlinov.speeding.captcha

import java.awt.BorderLayout
import java.awt.event.{KeyEvent, KeyListener}
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.{Files, Paths}

import cats.effect.implicits._
import cats.effect.{ConcurrentEffect, _}
import com.sksamuel.scrimage.ImmutableImage
import fs2.Stream
import fs2.concurrent._
import javax.imageio.ImageIO
import javax.swing._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
// import scala.jdk.CollectionConverters._

object LabelData extends ImageProcessor with SimpleSolver with DatasetSettings {
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  def main(args: Array[String]): Unit = {
    val allDigitsFiles = Files.newDirectoryStream(digitsPath).asScala.map(p => new File(p.toUri)).toList
    val allSignsFiles = Files.newDirectoryStream(signsPath).asScala.map(p => new File(p.toUri)).toList
    val files = (allSignsFiles ++ allDigitsFiles).filter(!_.isDirectory)
    // val files = (allDigitsFiles ++ allSignsFiles).filter(!_.isDirectory)
    val solver = createSolver
    setupUI[IO](files, solver)
      .compile
      .drain
      .unsafeRunSync()
  }

  private def setupUI[F[_]: ConcurrentEffect](files: List[File], solver: SimpleSolver.Solver): Stream[F, Unit] = {
    val frame = new JFrame
    val imageIcon = new ImageIcon()
    val imageLabel = new JLabel(imageIcon)
    val textBox = new JLabel("prediction")
    frame.getContentPane.add(imageLabel, BorderLayout.CENTER)
    frame.getContentPane.add(textBox, BorderLayout.SOUTH)
    frame.setSize(100, 100)
    val mainPanel = new JPanel(new BorderLayout())
    mainPanel.add(imageLabel)
    frame.add(mainPanel)
    frame.setVisible(true)
    def setImageUnsafe(image: BufferedImage, prediction: Char): Unit = {
      imageIcon.setImage(image)
      textBox.setText(prediction.toString)
      imageLabel.repaint()
    }
    def setImage(image: BufferedImage, prediction: Char): Unit = {
      val prediction = solver.predict(ImmutableImage.fromAwt(image))
      SwingUtilities.invokeLater(() => setImageUnsafe(image, prediction))
    }
    val kbEvents = for {
      q <- Stream.eval(Queue.unbounded[F, KeyEvent]) // try Queue.synchronous
      _ = frame.addKeyListener(createKeyListener(q))
      ev <- q.dequeue
    } yield ev
    Stream
      .emits(files)
      .chunkN(1, allowFewer = false)
      .map(_.map { imageFile =>
        val image = ImageIO.read(imageFile)
        val probabilities = solver.probabilities(ImmutableImage.fromAwt(image))
        (imageFile, image, probabilities)
      })
      .collect { case chunk if chunk.forall { case (file, _, probs) => !trySave(file, probs) } =>
        chunk.map { case (file, image, probs) =>
          setImage(image, probs.maxBy(_._2)._1)
          file
        }
      }
      .zip(kbEvents)
      .map { case (fileChunk, e) =>
        val dirName = KeyEvent.getKeyText(e.getKeyCode)
        fileChunk.foreach(moveFile(dirName, _))
      }
  }

  private def trySave(file: File, probs: Seq[(Char, Float)]): Boolean = {
    val mostProbable = probs.maxBy(_._2)
    val otherProbs = probs.toSet - mostProbable
    val criteria = mostProbable._2 == 1f || (mostProbable._2 >= 0.99f && !otherProbs.exists(_._2 > 0.85f))
    if (criteria) {
      val othersMax = otherProbs.maxBy(_._2)
      logger.info(f"Criteria OK: ${mostProbable._2 * 100}%.2f%%, " +
        f"next is ${othersMax._1} with ${othersMax._2 * 100}%.2f%%")
      val dirName = mostProbable._1.toString
      moveFile(dirName, file)
    }
    criteria
  }

  private def moveFile(dirName: String, file: File): Unit = {
    val filePath = Paths.get(file.toURI)
    val parentDir = Paths.get(file.getParentFile.toURI)
    val dstDir = {
      val dir = if (dirName == "=") "+" else dirName
      parentDir.resolve(dir)
    }
    if (!Files.exists(dstDir)) Files.createDirectory(dstDir)
    val dstPath = dstDir.resolve(file.getName)
    Files.move(filePath, dstPath)
    logger.info(s"Moved to $dstPath")
  }

  private def createKeyListener[F[_]: ConcurrentEffect](q: Queue[F, KeyEvent]): KeyListener = new KeyListener {
    override def keyTyped(e: KeyEvent): Unit = ()

    override def keyPressed(e: KeyEvent): Unit = ()

    override def keyReleased(e: KeyEvent): Unit = q.enqueue1(e).toIO.unsafeRunSync()
  }
}
