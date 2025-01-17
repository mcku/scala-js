/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.testsuite.niocharset

import scala.language.implicitConversions

import scala.util._

import java.nio._
import java.nio.charset._

import org.junit.Assert._

class BaseCharsetTest(val charset: Charset) {
  import BaseCharsetTest._

  protected val AllErrorActions = Seq(
      CodingErrorAction.IGNORE,
      CodingErrorAction.REPLACE,
      CodingErrorAction.REPORT)

  protected val ReportActions = Seq(
      CodingErrorAction.REPORT)

  protected def testDecode(in: ByteBuffer)(
      outParts: OutPart[CharBuffer]*): Unit = {

    def newDecoder(malformedAction: CodingErrorAction,
        unmappableAction: CodingErrorAction): CharsetDecoder = {
      val decoder = charset.newDecoder
      decoder.onMalformedInput(malformedAction)
      decoder.onUnmappableCharacter(unmappableAction)
      decoder
    }

    def prepareInputBuffer(readOnly: Boolean): ByteBuffer = {
      val buf =
        if (readOnly) in.asReadOnlyBuffer()
        else in.duplicate()
      assert(buf.asInstanceOf[java.nio.Buffer].isReadOnly == readOnly)
      assert(buf.hasArray != readOnly)
      buf
    }

    def testDecodeVsSteppedDecode(malformedAction: CodingErrorAction,
        unmappableAction: CodingErrorAction, readOnly: Boolean): Unit = {

      /* This test compares the decode result of a single decoder.decode(buffer)
       * call vs repeated decode(input, output, done) calls, with a buffer that
       * increments its limit each step. This will catch mishandled broken up
       * multi-byte sequences.
       */

      val decoder = newDecoder(malformedAction, unmappableAction)

      val directResult = Try {
        decoder.decode(prepareInputBuffer(readOnly)).toString()
      }

      val inputForIncremental = prepareInputBuffer(readOnly)
      inputForIncremental.limit(inputForIncremental.position())

      val outputBuffer = CharBuffer.allocate(in.capacity() * 2)
      decoder.reset()

      def increaseBuffer(bb: ByteBuffer): Boolean =
        (bb.limit() < bb.capacity()) && (bb.limit(bb.limit() + 1) != null)

      val incrementalResult = Try {
        var result: CoderResult = CoderResult.UNDERFLOW
        while (increaseBuffer(inputForIncremental) && result.isUnderflow) {
          result = decoder.decode(inputForIncremental, outputBuffer, false)
        }
        if (result.isError)
          result.throwException()
        result = decoder.decode(inputForIncremental, outputBuffer, true)
        if (result.isError)
          result.throwException()
        result = decoder.flush(outputBuffer)
        if (result.isError)
          result.throwException()
        outputBuffer.flip()
        outputBuffer.toString()
      }

      (directResult, incrementalResult) match {
        case (Success(directString), Success(incrementalString)) =>
          assertEquals(incrementalString, directString)

        case (Failure(directEx: UnmappableCharacterException),
            Failure(incrementalEx: UnmappableCharacterException)) =>
          assertEquals(incrementalEx.getInputLength, directEx.getInputLength)

        case (Failure(directEx: MalformedInputException),
            Failure(incrementalEx: MalformedInputException)) =>
          assertEquals(incrementalEx.getInputLength, directEx.getInputLength)

        case _ =>
          // Assert false, but display an informational failure message
          assertSame(directResult, incrementalResult)
      }
    }

    def testOneConfig(malformedAction: CodingErrorAction,
        unmappableAction: CodingErrorAction, readOnly: Boolean): Unit = {

      val decoder = newDecoder(malformedAction, unmappableAction)

      val inBuf = prepareInputBuffer(readOnly)

      val actualTry = Try {
        val buf = decoder.decode(inBuf)
        val actualChars = new Array[Char](buf.remaining())
        buf.get(actualChars)
        actualChars
      }

      val expectedTry = Try {
        val expectedChars = Array.newBuilder[Char]
        outParts foreach {
          case BufferPart(buf) =>
            val bufArray = new Array[Char](buf.remaining)
            buf.mark()
            try buf.get(bufArray)
            finally buf.reset()
            expectedChars ++= bufArray
          case Malformed(len) =>
            malformedAction match {
              case CodingErrorAction.IGNORE  =>
                expectedChars
              case CodingErrorAction.REPLACE =>
                expectedChars ++= decoder.replacement()
              case CodingErrorAction.REPORT  =>
                throw new MalformedInputException(len)
            }
          case Unmappable(len) =>
            unmappableAction match {
              case CodingErrorAction.IGNORE  =>
                expectedChars
              case CodingErrorAction.REPLACE =>
                expectedChars ++= decoder.replacement()
              case CodingErrorAction.REPORT  =>
                throw new UnmappableCharacterException(len)
            }
        }
        expectedChars.result()
      }

      (actualTry, expectedTry) match {
        case (Failure(actualEx: MalformedInputException),
            Failure(expectedEx: MalformedInputException)) =>
          assertEquals(expectedEx.getInputLength(), actualEx.getInputLength())

        case (Failure(actualEx: UnmappableCharacterException),
            Failure(expectedEx: UnmappableCharacterException)) =>
          assertEquals(expectedEx.getInputLength(), actualEx.getInputLength())

        case (Success(actualChars), Success(expectedChars)) =>
          assertArrayEquals(expectedChars.map(_.toInt), actualChars.map(_.toInt))

        case _ =>
          // For the error message
          assertSame(expectedTry, actualTry)
      }
    }

    val hasAnyMalformed = outParts.exists(_.isInstanceOf[Malformed])
    val hasAnyUnmappable = outParts.exists(_.isInstanceOf[Unmappable])

    for {
      malformedAction  <- if (hasAnyMalformed)  AllErrorActions else ReportActions
      unmappableAction <- if (hasAnyUnmappable) AllErrorActions else ReportActions
      readOnly         <- List(false, true)
    } {
      testDecodeVsSteppedDecode(malformedAction, unmappableAction, readOnly)
      testOneConfig(malformedAction, unmappableAction, readOnly)
    }
  }

  protected def testEncode(in: CharBuffer)(
      outParts: OutPart[ByteBuffer]*): Unit = {

    def testOneConfig(malformedAction: CodingErrorAction,
        unmappableAction: CodingErrorAction, readOnly: Boolean): Unit = {

      val encoder = charset.newEncoder()
      encoder.onMalformedInput(malformedAction)
      encoder.onUnmappableCharacter(unmappableAction)

      val inBuf =
        if (readOnly) in.asReadOnlyBuffer()
        else in.duplicate()
      assertTrue(inBuf.asInstanceOf[java.nio.Buffer].isReadOnly == readOnly)
      assertTrue(inBuf.hasArray != readOnly)

      val actualTry = Try {
        val buf = encoder.encode(inBuf)
        val actualBytes = new Array[Byte](buf.remaining())
        buf.get(actualBytes)
        actualBytes
      }

      val expectedTry = Try {
        val expectedBytes = Array.newBuilder[Byte]
        outParts foreach {
          case BufferPart(buf) =>
            val bufArray = new Array[Byte](buf.remaining)
            buf.mark()
            try buf.get(bufArray)
            finally buf.reset()
            expectedBytes ++= bufArray
          case Malformed(len) =>
            malformedAction match {
              case CodingErrorAction.IGNORE  =>
                expectedBytes
              case CodingErrorAction.REPLACE =>
                expectedBytes ++= encoder.replacement()
              case CodingErrorAction.REPORT  =>
                throw new MalformedInputException(len)
            }
          case Unmappable(len) =>
            unmappableAction match {
              case CodingErrorAction.IGNORE  =>
                expectedBytes
              case CodingErrorAction.REPLACE =>
                expectedBytes ++= encoder.replacement()
              case CodingErrorAction.REPORT  =>
                throw new UnmappableCharacterException(len)
            }
        }
        expectedBytes.result()
      }

      (actualTry, expectedTry) match {
        case (Failure(actualEx: MalformedInputException),
            Failure(expectedEx: MalformedInputException)) =>
          assertEquals(expectedEx.getInputLength(), actualEx.getInputLength())

        case (Failure(actualEx: UnmappableCharacterException),
            Failure(expectedEx: UnmappableCharacterException)) =>
          assertEquals(expectedEx.getInputLength(), actualEx.getInputLength())

        case (Success(actualBytes), Success(expectedBytes)) =>
          assertArrayEquals(expectedBytes, actualBytes)

        case _ =>
          // For the error message
          assertSame(expectedTry, actualTry)
      }
    }

    val hasAnyMalformed = outParts.exists(_.isInstanceOf[Malformed])
    val hasAnyUnmappable = outParts.exists(_.isInstanceOf[Unmappable])

    for {
      malformedAction  <- if (hasAnyMalformed)  AllErrorActions else ReportActions
      unmappableAction <- if (hasAnyUnmappable) AllErrorActions else ReportActions
      readOnly         <- List(false, true)
    } {
      testOneConfig(malformedAction, unmappableAction, readOnly)
    }
  }
}

object BaseCharsetTest {
  sealed abstract class OutPart[+BufferType <: Buffer]
  final case class BufferPart[BufferType <: Buffer](buf: BufferType) extends OutPart[BufferType]
  final case class Malformed(length: Int) extends OutPart[Nothing]
  final case class Unmappable(length: Int) extends OutPart[Nothing]

  object OutPart {
    implicit def fromBuffer[BufferType <: Buffer](buf: BufferType): BufferPart[BufferType] =
      BufferPart(buf)
  }

  implicit class Interpolators private[BaseCharsetTest] (
      private val sc: StringContext)
      extends AnyVal {

    def bb(args: Any*): ByteBuffer = {
      val strings = sc.parts.iterator
      val expressions = args.iterator
      val buf = Array.newBuilder[Byte]

      def appendStr(s: String): Unit = {
        val s1 = s.replace(" ", "")
        require(s1.length % 2 == 0)
        for (i <- 0 until s1.length by 2)
          buf += java.lang.Integer.parseInt(s1.substring(i, i+2), 16).toByte
      }

      appendStr(strings.next())
      while (strings.hasNext) {
        expressions.next() match {
          case b: Byte            => buf += b
          case bytes: Array[Byte] => buf ++= bytes
          case bytes: Seq[_]      =>
            buf ++= bytes.map(_.asInstanceOf[Number].byteValue())
        }
        appendStr(strings.next())
      }

      ByteBuffer.wrap(buf.result())
    }

    def cb(args: Any*): CharBuffer =
      CharBuffer.wrap(sc.s(args: _*).toCharArray)
  }
}
