/*
 * =========================================================================================
 * Copyright © 2013-2018 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.http4s

import cats.effect.kernel.Concurrent
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Sync}
import kamon.http4s.middleware.server.KamonSupport
import kamon.trace.Span
import org.http4s.{Headers, HttpRoutes}
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.server.Server
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, OptionValues, WordSpec}

import scala.concurrent.ExecutionContext
import org.http4s.implicits._
import cats.implicits._
import kamon.testkit.TestSpanReporter
import kamon.tag.Lookups.{plain, plainLong}
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.blaze.server.BlazeServerBuilder
import org.typelevel.ci.CIString

class ServerInstrumentationSpec extends WordSpec
  with Matchers
  with Eventually
  with SpanSugar
  with OptionValues
  with TestSpanReporter
  with BeforeAndAfterAll {
  private implicit val runtime: IORuntime = cats.effect.unsafe.IORuntime.global

  val srv =
    BlazeServerBuilder[IO](ExecutionContext.global)
      .bindAny()
      .withExecutionContext(ExecutionContext.global)
      .withHttpApp(KamonSupport(HttpRoutes.of[IO] {
          case GET -> Root / "tracing" / "ok" =>  Ok("ok")
          case GET -> Root / "tracing" / "error"  => InternalServerError("error!")
          case GET -> Root / "tracing" / "errorinternal"  => throw new RuntimeException("ble")
          case GET -> Root / "tracing" / name / "ok" =>  Ok(s"ok $name")
        }
      ,"", 0).orNotFound)
    .resource

  val client =
    BlazeClientBuilder[IO](ExecutionContext.global).resource

  def withServerAndClient[A](f: (Server, Client[IO]) => IO[A]): A =
    (srv, client).tupled.use(f.tupled).unsafeRunSync()

  private def getResponse[F[_]: Concurrent](path: String)(server: Server, client: Client[F]): F[(String, Headers)] = {
    client.get(s"http://127.0.0.1:${server.address.getPort}$path"){ r =>
      r.bodyText.compile.toList.map(_.mkString).map(_ -> r.headers)
    }
  }

  "The Server instrumentation" should {
    "propagate the current context and respond to the ok action" in withServerAndClient { (server, client) =>
      val request = getResponse("/tracing/ok")(server, client).map { case (body, headers) =>
        headers.get(CIString("trace-id")).isDefined shouldBe true
        body should startWith("ok")
      }

      val test = IO {
        eventually(timeout(5.seconds)) {
          val span = testSpanReporter().nextSpan().value

          span.operationName shouldBe "/tracing/ok"
          span.kind shouldBe Span.Kind.Server
          span.metricTags.get(plain("component")) shouldBe "http4s.server"
          span.metricTags.get(plain("http.method")) shouldBe "GET"
          span.metricTags.get(plainLong("http.status_code")) shouldBe 200
        }
      }

      request *> test
    }

    "propagate the current context and respond to the not-found action" in withServerAndClient { (server, client) =>
      val request = getResponse("/tracing/not-found")(server, client).map { case (body, headers) =>
        headers.get(CIString("trace-id")).isDefined shouldBe true
      }

      val test = IO {
        eventually(timeout(5.seconds)) {
          val span = testSpanReporter.nextSpan().value

          span.operationName shouldBe "unhandled"
          span.kind shouldBe Span.Kind.Server
          span.metricTags.get(plain("component")) shouldBe "http4s.server"
          span.metricTags.get(plain("http.method")) shouldBe "GET"
          span.metricTags.get(plainLong("http.status_code")) shouldBe 404
        }
      }

      request *> test
    }

    "propagate the current context and respond to the error action" in withServerAndClient { (server, client) =>
      val request = getResponse("/tracing/error")(server, client).map { case (body, headers) =>
        headers.get(CIString("trace-id")).isDefined shouldBe true
        body should startWith("error!")
      }

      val test = IO {
        eventually(timeout(5.seconds)) {
          val span = testSpanReporter.nextSpan().value

          span.operationName shouldBe "/tracing/error"
          span.kind shouldBe Span.Kind.Server
          span.hasError shouldBe true
          span.metricTags.get(plain("component")) shouldBe "http4s.server"
          span.metricTags.get(plain("http.method")) shouldBe "GET"
          span.metricTags.get(plainLong("http.status_code")) shouldBe 500
        }
      }

      request *> test
    }
    "propagate the current context and respond to the error while processing" in withServerAndClient { (server, client) =>
      val request = getResponse("/tracing/errorinternal")(server, client)
      /* TODO serviceErrorHandler kicks in and rewrites response, loosing trace information
        .map { case (body, headers) =>
          headers.exists(_.name == CaseInsensitiveString("trace-id")) shouldBe true
        }
      */

      val test = IO {
        eventually(timeout(5.seconds)) {
          val span = testSpanReporter.nextSpan().value

          span.operationName shouldBe "/tracing/errorinternal"
          span.kind shouldBe Span.Kind.Server
          span.hasError shouldBe true
          span.metricTags.get(plain("component")) shouldBe "http4s.server"
          span.metricTags.get(plain("http.method")) shouldBe "GET"
          span.metricTags.get(plainLong("http.status_code")) shouldBe 500
        }
      }

      request *> test
    }

    "handle path parameter" in withServerAndClient { (server, client) =>
      val request: IO[(String, Headers)] =
        getResponse("/tracing/bazz/ok")(server, client)
      val test = IO {
        eventually(timeout(5.seconds)) {
          val span = testSpanReporter.nextSpan().value

          span.operationName shouldBe "/tracing/:name/ok"
          span.kind shouldBe Span.Kind.Server
          span.hasError shouldBe false
          span.metricTags.get(plain("component")) shouldBe "http4s.server"
          span.metricTags.get(plain("http.method")) shouldBe "GET"
          span.metricTags.get(plainLong("http.status_code")) shouldBe 200
        }
      }

      request *> test
    }
  }
}
