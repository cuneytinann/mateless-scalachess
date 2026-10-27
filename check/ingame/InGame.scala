// The in-game lock draw (Position.lockedOut, asked at halfmove clock 0 or 1), checked against
// scalachess's own expectations and timed. Uses only autoDraw/end, so it runs on any build.
//   dead <fens>        per FEN: autoDraw (1/0)
//   time <fens> [n]    ns per call of position.autoDraw
//   cases <moves>      LockTest's and AutodrawTest's expectations (moves: fivefold_moves.txt, AutodrawTest's fivefold game)
import chess.*
import chess.Square.*
import chess.format.Fen
import chess.variant.Standard
import scala.io.Source

def read(fen: String): Option[Position] = Fen.readWithMoveNumber(Standard, Fen.Full(fen)).map(_.position)
def lines(path: String): Vector[String] =
  val s = Source.fromFile(path, "UTF-8")
  try s.getLines().toVector
  finally s.close()
def play(p: Position, ms: Seq[(Square, Square)]): Position =
  ms.foldLeft(p)((q, m) => q.move(m._1, m._2, None).toOption.get.after)

@main def ingame(command: String, path: String, rest: String*): Unit =
  command match
    case "dead" =>
      val out = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.OutputStreamWriter(System.out), 1 << 16))
      for f <- lines(path) if f.nonEmpty do
        out.println(read(f).fold("err")(p => if p.autoDraw then "1" else "0"))
      out.flush()
    case "time" =>
      val ps = lines(path).filter(_.nonEmpty).flatMap(read).toArray
      val rounds = rest.headOption.fold(9)(_.toInt)
      for r <- 1 to rounds do
        val t0 = System.nanoTime
        var n = 0
        var i = 0
        while i < ps.length do
          if ps(i).autoDraw then n += 1
          i += 1
        println(f"round $r: ${(System.nanoTime - t0).toDouble / ps.length}%.1f ns/call over ${ps.length}, $n draws")
    case "cases" =>
      def at(fen: String) = read(fen).get
      val five = lines(path).filter(_.nonEmpty).map(l => (Square.fromKey(l.split(' ')(0).toLowerCase).get, Square.fromKey(l.split(' ')(1).toLowerCase).get))
      val list: List[(String, Boolean, Boolean)] = List(
        // LockTest
        ("locked 1", at("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3K4 b - - 0 92").autoDraw, true),
        ("locked 2", at("3k4/8/3p4/2pP1p1p/p1P2PpP/P2K2P1/8/8 b - - 1 56").autoDraw, true),
        ("locked 3", at("8/8/4k3/p2p2p1/P2P2P1/2K5/8/8 w - - 0 75").autoDraw, true),
        ("locked 1 ends the game", at("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3K4 b - - 0 92").end, true),
        ("clock 1", at("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3K4 b - - 1 92").autoDraw, true),
        ("clock 2", at("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3K4 b - - 2 92").autoDraw, false),
        ("pawn move that locks", play(at("8/8/4k3/3p2p1/1p1P1pP1/1P6/5P2/3K4 w - - 3 91"), List(F2 -> F3)).autoDraw, true),
        ("king in a pawn's check", at("8/8/1k6/p2p2p1/PK1P2Pp/7P/8/8 w - - 0 49").autoDraw, false),
        ("king steps out of a pawn's check", play(at("8/8/1k6/p2p2p1/PK1P2Pp/7P/8/8 w - - 0 49"), List(B4 -> C3)).autoDraw, true),
        ("a knight", at("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3KN3 b - - 0 92").autoDraw, false),
        ("a pawn can capture", at("8/8/4k3/3p2p1/1p1P1pP1/1P2P3/8/3K4 b - - 0 92").autoDraw, false),
        // AutodrawTest
        ("new game", Standard.initialPosition.autoDraw, false),
        ("opened", play(Standard.initialPosition, List(E2 -> E4, C7 -> C5, C2 -> C3, D7 -> D5, E4 -> D5)).autoDraw, false),
        ("two knights", at("1n2k1n1/8/8/8/8/8/8/4K3 w - - 0 1").autoDraw, false),
        ("knight versus pawn", play(at("7K/5k2/7P/6n1/8/8/8/8 b - - 0 40"), List(F7 -> F8)).autoDraw, false),
        ("bishops versus pawn", play(at("1b1b3K/8/5k1P/8/8/8/8/8 b - - 0 40"), List(B8 -> E5)).autoDraw, false),
        ("bishops versus queen 1", play(at("b2b3K/8/5k1Q/8/8/8/8/8 b - -"), List(F6 -> E5)).autoDraw, false),
        ("bishops versus queen 2", play(at("1b1b3K/8/5k1Q/8/8/8/8/8 b - -"), List(F6 -> E5)).autoDraw, false),
        ("knight versus pawns", play(at("8/8/5N2/8/6p1/8/5K1p/7k w - - 0 37"), List(F6 -> E4)).autoDraw, false),
        ("knight versus pieces", play(at("8/8/8/4N3/4k1p1/6K1/8/3b4 w - - 5 59"), List(E5 -> F7)).autoDraw, false),
        ("opposite bishops with queen", play(at("8/8/3Q4/2bK4/B7/8/8/k7 b - - 0 67"), List(A1 -> B2)).autoDraw, false),
        ("same-color bishops", at("5K2/8/8/1B6/8/k7/6b1/8 w - - 0 39").autoDraw, true),
        ("fivefold", play(Standard.initialPosition, five).autoDraw, true),
        ("not yet fivefold", play(Standard.initialPosition, five.dropRight(1)).autoDraw, false)
      )
      var bad = 0
      for (name, got, want) <- list do
        if got != want then bad += 1
        println(f"${if got == want then "ok  " else "FAIL"} $name%-36s want $want%-5s got $got")
      println(s"${list.size} cases, $bad failures")
