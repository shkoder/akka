/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.io

import java.nio.file.StandardOpenOption.{ CREATE, WRITE }
import java.nio.file.{ Files, Path, StandardOpenOption }

import akka.actor.ActorSystem
import akka.dispatch.ExecutionContexts
import akka.stream.impl.PhasedFusingActorMaterializer
import akka.stream.impl.StreamSupervisor
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.scaladsl.{ FileIO, Sink, Source }
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.{ ActorAttributes, ActorMaterializer, ActorMaterializerSettings, IOResult }
import akka.util.{ ByteString, Timeout }
import com.google.common.jimfs.{ Configuration, Jimfs }
import org.scalatest.BeforeAndAfterAll

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class FileSinkSpec extends StreamSpec(UnboundedMailboxConfig) {

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)
  val fs = Jimfs.newFileSystem("FileSinkSpec", Configuration.unix())

  val TestLines = {
    val b = ListBuffer[String]()
    b.append("a" * 1000 + "\n")
    b.append("b" * 1000 + "\n")
    b.append("c" * 1000 + "\n")
    b.append("d" * 1000 + "\n")
    b.append("e" * 1000 + "\n")
    b.append("f" * 1000 + "\n")
    b.toList
  }

  val TestByteStrings = TestLines.map(ByteString(_))

  "FileSink" must {
    "write lines to a file" in assertAllStagesStopped {
      targetFile { f ⇒
        val completion = Source(TestByteStrings)
          .runWith(FileIO.toPath(f))

        val result = Await.result(completion, 3.seconds)
        result.count should equal(6006)
        checkFileContents(f, TestLines.mkString(""))
      }
    }

    "create new file if not exists" in assertAllStagesStopped {
      targetFile({ f ⇒
        val completion = Source(TestByteStrings)
          .runWith(FileIO.toPath(f))

        val result = Await.result(completion, 3.seconds)
        result.count should equal(6006)
        checkFileContents(f, TestLines.mkString(""))
      }, create = false)
    }

    "write into existing file without wiping existing data" in assertAllStagesStopped {
      targetFile { f ⇒
        def write(lines: List[String]) =
          Source(lines)
            .map(ByteString(_))
            .runWith(FileIO.toPath(f, Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE)))

        val completion1 = write(TestLines)
        Await.result(completion1, 3.seconds)

        val lastWrite = List("x" * 100)
        val completion2 = write(lastWrite)
        val result = Await.result(completion2, 3.seconds)

        result.count should ===(lastWrite.flatten.length)
        checkFileContents(f, lastWrite.mkString("") + TestLines.mkString("").drop(100))
      }
    }

    "by default replace the existing file" in assertAllStagesStopped {
      targetFile { f ⇒
        def write(lines: List[String]) =
          Source(lines)
            .map(ByteString(_))
            .runWith(FileIO.toPath(f))

        val completion1 = write(TestLines)
        Await.result(completion1, 3.seconds)

        val lastWrite = List("x" * 100)
        val completion2 = write(lastWrite)
        val result = Await.result(completion2, 3.seconds)

        result.count should ===(lastWrite.flatten.length)
        checkFileContents(f, lastWrite.mkString(""))
      }
    }

    "allow appending to file" in assertAllStagesStopped {
      targetFile { f ⇒
        def write(lines: List[String] = TestLines) =
          Source(lines)
            .map(ByteString(_))
            .runWith(FileIO.toPath(f, Set(StandardOpenOption.APPEND)))

        val completion1 = write()
        val result1 = Await.result(completion1, 3.seconds)

        val lastWrite = List("x" * 100)
        val completion2 = write(lastWrite)
        val result2 = Await.result(completion2, 3.seconds)

        Files.size(f) should ===(result1.count + result2.count)
        checkFileContents(f, TestLines.mkString("") + lastWrite.mkString(""))
      }
    }

    "allow writing from specific position to the file" in assertAllStagesStopped {
      targetFile { f ⇒
        val TestLinesCommon = {
          val b = ListBuffer[String]()
          b.append("a" * 1000 + "\n")
          b.append("b" * 1000 + "\n")
          b.append("c" * 1000 + "\n")
          b.append("d" * 1000 + "\n")
          b.toList
        }

        val commonByteString = TestLinesCommon.map(ByteString(_)).foldLeft[ByteString](ByteString.empty)((acc, line) ⇒ acc ++ line).compact
        val startPosition = commonByteString.size

        val testLinesPart2: List[String] = {
          val b = ListBuffer[String]()
          b.append("x" * 1000 + "\n")
          b.append("x" * 1000 + "\n")
          b.toList
        }

        def write(lines: List[String] = TestLines, startPosition: Long = 0) =
          Source(lines)
            .map(ByteString(_))
            .runWith(FileIO.toPath(f, options = Set(WRITE, CREATE), startPosition = startPosition))

        val completion1 = write()
        val result1 = Await.result(completion1, 3.seconds)

        val completion2 = write(testLinesPart2, startPosition)
        val result2 = Await.result(completion2, 3.seconds)

        Files.size(f) should ===(startPosition + result2.count)
        checkFileContents(f, TestLinesCommon.mkString("") + testLinesPart2.mkString(""))
      }
    }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      targetFile { f ⇒
        val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
        val materializer = ActorMaterializer()(sys)
        try {
          Source.fromIterator(() ⇒ Iterator.continually(TestByteStrings.head)).runWith(FileIO.toPath(f))(materializer)

          materializer.asInstanceOf[PhasedFusingActorMaterializer].supervisor.tell(StreamSupervisor.GetChildren, testActor)
          val ref = expectMsgType[Children].children.find(_.path.toString contains "fileSink").get
          assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher")
        } finally shutdown(sys)
      }
    }

    "allow overriding the dispatcher using Attributes" in assertAllStagesStopped {
      targetFile { f ⇒
        val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
        val materializer = ActorMaterializer()(sys)

        try {
          Source.fromIterator(() ⇒ Iterator.continually(TestByteStrings.head))
            .to(FileIO.toPath(f).addAttributes(ActorAttributes.dispatcher("akka.actor.default-dispatcher")))
            .run()(materializer)

          materializer.asInstanceOf[PhasedFusingActorMaterializer].supervisor.tell(StreamSupervisor.GetChildren, testActor)
          val ref = expectMsgType[Children].children.find(_.path.toString contains "fileSink").get
          assertDispatcher(ref, "akka.actor.default-dispatcher")
        } finally shutdown(sys)
      }
    }

    "write single line to a file from lazy sink" in assertAllStagesStopped {
      //LazySink must wait for result of initialization even if got upstreamComplete
      targetFile { f ⇒
        val completion = Source(List(TestByteStrings.head))
          .runWith(Sink.lazyInit[ByteString, Future[IOResult]](
            _ ⇒ Future.successful(FileIO.toPath(f)), () ⇒ Future.successful(IOResult.createSuccessful(0)))
            .mapMaterializedValue(_.flatMap(identity)(ExecutionContexts.sameThreadExecutionContext)))

        Await.result(completion, 3.seconds)

        checkFileContents(f, TestLines.head)
      }
    }

  }

  private def targetFile(block: Path ⇒ Unit, create: Boolean = true) {
    val targetFile = Files.createTempFile(fs.getPath("/"), "synchronous-file-sink", ".tmp")
    if (!create) Files.delete(targetFile)
    try block(targetFile) finally Files.delete(targetFile)
  }

  def checkFileContents(f: Path, contents: String): Unit = {
    val out = Files.readAllBytes(f)
    new String(out) should ===(contents)
  }

  override def afterTermination(): Unit = {
    fs.close()
  }

}
