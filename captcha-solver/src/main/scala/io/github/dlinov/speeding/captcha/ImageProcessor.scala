package io.github.dlinov.speeding.captcha

import java.io.{ByteArrayOutputStream, StringReader}

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.color.Grayscale
import com.sksamuel.scrimage.filter.{BlackThresholdFilter, GrayscaleFilter}
import com.sksamuel.scrimage.implicits._
import com.sksamuel.scrimage.nio.ImmutableImageLoader
import fs2.{Pure, Stream}
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.batik.transcoder.{TranscoderInput, TranscoderOutput}

object ImageProcessor {

  case class DigitsAndSign(digit1: ImmutableImage, digit2: ImmutableImage, sign: ImmutableImage)

  implicit class RichImage(val img: ImmutableImage) extends AnyVal {
    def byteStream: Stream[Pure, Byte] = Stream.emits(img.bytes(writer).toVector)
  }

  private val White = new Grayscale(255)
  private val imageLoader = ImmutableImage.loader()
  private val transcoder = new PNGTranscoder()
  private val filters = Seq(
    new GrayscaleFilter,
    new BlackThresholdFilter(99)
  )

}

trait ImageProcessor {
  import ImageProcessor._

  def loader: ImmutableImageLoader = ImageProcessor.imageLoader

  def rasterize(svgString: String): ImmutableImage = {
    val reader = new StringReader(svgString)
    val input = new TranscoderInput(reader)
    val ostream = new ByteArrayOutputStream()
    val output = new TranscoderOutput(ostream)
    transcoder.transcode(input, output)
    reader.close()
    val bytes = ostream.toByteArray
    ostream.flush()
    ostream.close()
    loader.fromBytes(bytes)
  }

  def cutImageToThree(pngImage: ImmutableImage): DigitsAndSign = {
    val a = 35
    val yOff = 10
    val d1 = pngImage.subimage(20, yOff, a, a)
    val s = pngImage.subimage(60, yOff, a, a)
    val d2 = pngImage.subimage(94, yOff, a, a)
    DigitsAndSign(d1, d2, s)
  }

  def bw(image: ImmutableImage): ImmutableImage = {
    image
      .removeTransparency(White)
      .filter(filters: _*)
  }

  def bw(dds: DigitsAndSign): DigitsAndSign = {
    dds.copy(digit1 = bw(dds.digit1), digit2 = bw(dds.digit2), sign = bw(dds.sign))
  }

}
