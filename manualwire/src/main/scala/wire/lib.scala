package wire

import scala.language.higherKinds

trait Response {
  type Out
  def r: Out
}

trait Request {
  type Res <: Response
  type Out = Res#Out
}

trait Client[Req <: Request, Res <: Response, IO[_]] {
  def call[C <: Req, R <: Res, T](c: C { type Res = R; type Out = T }): IO[c.Out]
}
trait Server[Req <: Request, Res <: Response, IO[_]] {
  def dispatch[C <: Req, R <: Res](c: C { type Res = R }): IO[c.Res]
}
