package io.github.dlinov.speeding.captcha

import java.nio.file.Path

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.pixels.Pixel
import org.slf4j.LoggerFactory

object SimpleSolver {
  private val digits = '1' to '9'
  private val signs = List('+', '-')

  private object Solver {
    val BlackARGB = 0xff000000

    def create(masksPath: Path) = new Solver(masksPath)
  }

  class Solver private (masksPath: Path) extends ImageProcessor {
    private val logger = LoggerFactory.getLogger(getClass)
    private val digitsMasks = masks(digits)
    private val signsMasks = masks(signs)
    private val allMasks = digitsMasks ++ signsMasks
    private type Mask = (Char, Array[Pixel])

    def calculate(svgString: String): Int = {
      val rasterized = rasterize(svgString)
      val digitsAndSign = cutImageToThree(rasterized)
      val digit1 = predict(digitsAndSign.digit1, digitsMasks) - '0'
      val digit2 = predict(digitsAndSign.digit2, digitsMasks) - '0'
      val sign = predict(digitsAndSign.sign, signsMasks)
      if (sign == '+') digit1 + digit2 else digit1 - digit2
    }

    def predict(image: ImmutableImage): Char = {
      predict(image, allMasks)
    }

    def probabilities(image: ImmutableImage): Seq[(Char, Float)] = {
      calculateProbabilities(image, allMasks)
    }

    private def masks(labels: Seq[Char]): Seq[Mask] = {
      labels.map { label =>
        val maskName = masksPath.resolve(s"$label.png")
        label -> loader.fromPath(maskName).pixels()
      }
    }

    private def predict(image: ImmutableImage, masks: Seq[Mask]): Char = {
      val probabilities = calculateProbabilities(image, masks)
      logger.info(
        probabilities
          .map(p => f"${p._1}: ${p._2 * 100}%.2f%%")
          .mkString("Probabilities: ", ", ", "")
      )
      probabilities.maxBy(_._2)._1
    }

    private def calculateProbabilities(
        image: ImmutableImage,
        masks: Seq[Mask]
    ): Seq[(Char, Float)] = {
      val pixels = image.pixels()
      masks
        .map {
          case (ch, maskPixels) =>
            val valuablePixels = pixels
              .zip(maskPixels)
              .filter(px => px._2.argb == Solver.BlackARGB) // only black pixels from mask
            val diff = valuablePixels.count { case (px1, px2) => px1.argb != px2.argb }
            ch -> (1f - (diff.toFloat / valuablePixels.length))
        }
    }
  }
}

trait SimpleSolver { self: DatasetSettings =>

  def createSolver: SimpleSolver.Solver = SimpleSolver.Solver.create(masksPath)

}
