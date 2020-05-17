package io.github.dlinov.speeding.captcha

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.implicits._
import com.sksamuel.scrimage.pixels.Pixel
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
// import scala.jdk.CollectionConverters._

object SimpleModelBoot extends ImageProcessor with DatasetSettings {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val start = Instant.now
    val labeledImages = loadLabeledImages
    val masks = labeledImages.map { case (ch, images) => combineImages(ch, images, 0.98f) }
    val finish = Instant.now
    logger.info(labeledImages.foldLeft("Images stats:\n")((acc, entry) => acc + s"${entry._1}: ${entry._2.size}\n"))
    logger.info(s"Learning took ${Duration.between(start, finish).toMillis} ms")
    saveMasks(masks)
  }

  private def loadLabeledImages: Map[Char, Vector[ImmutableImage]] = {
    def load(path: Path, ch: Char) = {
      Files
        .list(path.resolve(ch.toString))
        .map[ImmutableImage](loader.fromPath(_))
        .iterator()
        .asScala
        .toVector
    }
    val digits = ('1' to '9').map(digit => digit -> load(digitsPath, digit)).toMap
    val signs = Seq('-', '+').map(sign => sign -> load(signsPath, sign)).toMap
    digits ++ signs
  }

  private def combineImages(ch: Char, images: Vector[ImmutableImage], threshold: Float): (Char, ImmutableImage) = {
    val blackARGB = 0xFF000000
    val whiteARGB = 0xFFFFFFFF
    val imgType = BufferedImage.TYPE_BYTE_BINARY
    def blackOrWhite(px: Pixel): Int = if (px.argb == blackARGB) 1 else 0
    val mask = if (images.nonEmpty) {
      val firstImage = images.head
      val initial = firstImage.pixels().map(px => (px.x, px.y, blackOrWhite(px)))
      val size = images.size.toFloat
      val pixelMask = images.tail.foldLeft(initial) {
        case (acc, img) =>
          acc.zip(img.pixels())
            .map { x =>
              x._1.copy(_3 = x._1._3 + blackOrWhite(x._2))
            }
      }.map { x =>
        val argb = if (x._3 / size > threshold) blackARGB else whiteARGB
        new Pixel(x._1, x._2, argb)
      }
      ImmutableImage.create(firstImage.width, firstImage.height, pixelMask, imgType)
    } else {
      ImmutableImage.fromAwt(new BufferedImage(0, 0, imgType))
    }
    ch -> mask
  }

  def saveMasks(masks: Map[Char, ImmutableImage]): Unit = {
    if (!Files.exists(masksPath)) {
      Files.createDirectories(masksPath)
    }
    masks.map {
      case (ch, image) =>
        image.output(writer, masksPath.resolve(s"$ch.png"))
    }
  }

}
