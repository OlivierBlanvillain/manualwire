# Manualwire

[![Travis](https://api.travis-ci.org/OlivierBlanvillain/manualwire.png?branch=master)](https://travis-ci.org/OlivierBlanvillain/manualwire)[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://github.com/OlivierBlanvillain/manualwire/issues/new?title=I%20want%20to%20chat,%20please%20open%20a%20Gitter)

Manualwire = [Autowire](https://github.com/lihaoyi/autowire) without the macros!

> That's impossible. ─ [@sjrd](https://github.com/sjrd)

Actually, it is possible, using a pair of (type member encoded) GADTs and a few path dependent types! This project is mainly about documenting the pattern rather that the published library. The implementation so [ridiculously small](manualwire/src/main/scala/wire/lib.scala), I wouldn't even bother depending on it.

# Example

Cross compiled:

```scala
// Shared API. If it was a trait, it would look like the following:
// (Spoiler: these definitions are isomorphic)

// trait Api[IO[_]] {
//   def foo(i: Int, j: Int): IO[Int]
//   def bar(i: Int, s: String): IO[List[String]]
// }

sealed trait MyRequest                   extends wire.Request
final case class Foo(i: Int, j: Int)     extends MyRequest { type Res = FooRes }
final case class Bar(i: Int, s: String)  extends MyRequest { type Res = BarRes }

sealed trait MyResponse                  extends wire.Response
final case class FooRes(r: String)       extends MyResponse { type Out = String }
final case class BarRes(r: List[String]) extends MyResponse { type Out = List[String] }
```

Server side:

```scala
object MyServer extends wire.Server[MyRequest, MyResponse, Future] {
  def foo(i: Int, j: Int): Future[String] = Future(i.to(j).sum.toString)
  def bar(i: Int, s: String): Future[List[String]] = Future(List.fill(i)(s))

  // Boilerplate (implementing the isomorphism)
  def dispatch(...) = ...

  // Websocket/AJAX plumbing
  ...
}
```

Client side:

```scala
object MyClient extends wire.Client[MyRequest, MyResponse, Future] {
  // Websocket/AJAX plumbing
  def call(...) = ...
}

MyClient.call(Foo(1, 5)): Future[Int]
MyClient.call(Bar("ah")): Future[Option[String]]
```


# Isomorphism between trait and pair of GADTs

A remote procedure call requires sending at least two messages over the wire: a request, emitted by the client, and a response, returned by the server. For example, calling `def foo(i: Int, j: Int): Future[String]` means sending a `(Int, Int)` on way, and a `(String)` the other. Manualwire uses case classes for these messages, `Foo` and `FooRes` in the example. (In contrast, Autowire uses an [internal case class](https://github.com/lihaoyi/autowire/blob/3dba5d596b85f05bef7e0a802631786e3270d4a8/autowire/shared/src/main/scala/autowire/Core.scala#L25) for its messages whose arguments are [macro generated](https://github.com/lihaoyi/autowire/blob/166aaf9aa7f9f03a2f9b509fef5e1dfcc3c15fca/autowire/shared/src/main/scala/autowire/Macros.scala#L262) from user defined method.)

All messages for of a client/server API can be organized using two traits, one for requests and one for the responses:

```scala
sealed trait MyRequest
final case class Foo(i: Int, j: Int) extends MyRequest

sealed trait MyResponse
final case class FooRes(r: Int) extends MyResponse
```

There is something important missing from this definition: there is not link between `Foo` and `FooRes`. This link can be expressed in the type system using a type member in every `Request` to point it's associated `Response`:

```scala
trait Request { type Res } // That's a GADT (with a type member instead of a type parameter)

sealed trait MyRequest extends Request
final case class Foo(i: Int, j: Int) extends MyRequest { type Res = MyResponse }

sealed trait MyResponse
final case class FooRes(r: Int) extends MyResponse
```

In order to infer `call(Foo(1, 2)): Future[Int]` (instead of `call(Foo(1, 2)): FooRes`) the final solution uses additional type information: an adding type member to every `Response` makes it possible "unwrap" the `Response` type from a `Request`:


```scala
package wire

trait Response {
  type Out
  def r: Out
}

trait Request {
  type Res <: Response
  type Out = Res#Out   // Unwrapped response type!
}
```

Putting it all together we obtain a heavily constrained pair of GADTs as an API definition. This formulation turns out to be isomorphic to a trait definition of an API.


# The call/dispatch methods

The `Request`/`Response` GADTs defined in the previous section are use to constrain the argument and return type of the `call` method (client side):

```scala
trait Client[Req <: Request, Res <: Response, IO[_]] {
  // Given a request, returns an IO of the associated response.
  def call[C <: Req, R <: Res, T](c: C { type Res = R; type Out = T }): IO[c.Out]
}
```

The server side counterpart of `call` is `dispatch`:

```scala
trait Server[Req <: Request, Res <: Response, IO[_]] {
  // Given a request, returns an IO of the associated response.
  def dispatch[C <: Req, R <: Res](c: C { type Res = R }): IO[c.Res]
}
```

The `dispatch` method must implement the correspondence between the `Request`/`Response` case classes and the server side implementation of these calls. Concretely, this is a pattern matching expression with one case per `Request` deconstructs the case class, does the actual calling method call, and warping the result in the corresponding `Response` case class:

```scala
object Server extends wire.Server[MyRequest, MyResponse, Future] {
  def foo(i: Int, j: Int): Future[String] = Future(i.to(j).sum.toString)
  def bar(i: Int, s: String): Future[List[String]] = Future(List.fill(i)(s))

  // Boilerplate (implementing the isomorphism)
  def dispatch[C <: MyRequest, R <: MyResponse](c: C { type Res = R }): Future[c.Res] =
    c match {
      case x: Foo => ((foo _).tupled)(Foo.unapply(x).get).map(FooRes.apply)
      case x: Bar => ((bar _).tupled)(Bar.unapply(x).get).map(BarRes.apply)
    }

  ... // Websocket/AJAX plumbing
}
```

Note that this pattern matching only typechecks when `MyRequest`/`MyResponse` are *sealed* and all their descendants are **final**. That's a corner case of scalac's pattern matching, without the **final** keyword the compiler isn't able to solve `c.Res =:= FooResponse` when `c: Foo`.

The `wire.Client` and `wire.Server` interfaces are parametric on the of type of IO used for communication. In practice this means that Manualwire can be used both with single value futures (`scala.concurrent.Future`/[`cats.effect.IO`](https://github.com/typelevel/cats-effect)), and with streaming abstraction ([`monix.reactive.Observable`](https://github.com/monix/monix/blob/v2.3.0/monix-reactive/shared/src/main/scala/monix/reactive/Observable.scala)).


# AJAX plumbing

Manualwire is agnostic of the mechanism used to transfer data between Scala systems. The `wire.Client` and `wire.Server` interfaces take as a third type parameter the abstraction used for network communication (`IO[_]`). In practice, `IO` can be instantiated with futures (`scala.concurrent.Future`/[`monix.eval.Task`](https://github.com/monix/monix/blob/master/monix-eval/shared/src/main/scala/monix/eval/Task.scala)/[`cats.effect.IO`](https://github.com/typelevel/cats-effect)), or, more interestingly, with a streaming abstractions ([`monix.reactive.Observable`](https://github.com/monix/monix/blob/v2.3.0/monix-reactive/shared/src/main/scala/monix/reactive/Observable.scala), [`fs2.Stream`](https://github.com/functional-streams-for-scala/fs2/blob/v0.10.0-M2/core/shared/src/main/scala/fs2/Stream.scala))

Future based communication over AJAX requests is by far the simplest solution. Indeed, given that a new connection is created for every request/response, the programming abstraction is perfectly aligned with the comunication mechanism. A Scala.js client implemented with [`scalajs.dom`](https://github.com/scala-js/scala-js-dom) and [`circe`](https://github.com/circe/circe) fits on a slide:

```scala
import org.scalajs.dom.ext.Ajax
import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

object MyClient extends wire.Client[MyRequest, MyResponse, Future] {
  // AJAX plumbing
  def call[C <: Req, R <: Res, T](c: C { type Res = R; type Out = T }): Future[c.Out] = {
    val promise = Promise[c.Out]()

    Ajax.post("/api", c.asJson.noSpaces).foreach { xhr =>
      decode[MyResponse](xhr.responseText) match {
        case Right(r: c.Out) => future.complete(r)
        case Left(e)         => future.failed(e)
      }
    }

    promise.future
  }
}
```

The corresponding server implementation using [`http4s`](https://github.com/http4s/http4s) and [`circe`](https://github.com/circe/circe) is equally simple:

```scala
import org.http4s._, org.http4s.dsl._
import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

object MyServer extends wire.Server[MyRequest, MyResponse, Future] {
  def dispatch(...) = ...

  // AJAX plumbing
  val http4sService = HttpService {
    case POST -> Root / "api" =>
      decode[MyRequest](req.body) match {
        case Right(r) => Ok(dispatch(r).asJson.noSpaces)
        case Left(e)  => Ok(Future.failed(e))
      }
  }
}
```

# Websocket plumbing

Remote procedure calls over Websocket connections require more infrastructure. Indeed, because the request/response(s) abstraction is decoupled from the communication mechanism, both sides of the communication need to keep track of additional state relates messages with their associated requests and responses. This can be done by generating a UUID for each call and tagging messages to associate them with call. A sample implementation with streaming RPC on top of ([`monix.reactive.Observable`](https://github.com/monix/monix/blob/v2.3.0/monix-reactive/shared/src/main/scala/monix/reactive/Observable.scala) is available in the [examples](https://todo).
