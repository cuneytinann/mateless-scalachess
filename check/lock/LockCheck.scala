// The lock-only patch (a), against Mateless's JavaScript engine and the Lichess timeouts:
//   locks <fens>       per FEN: White flagged, Black flagged (1/0), for comparison with lock_ref.js
//   coverage <csv>     the flag-fall verdict (opponentHasInsufficientMaterial) with the patch, against
//                      scalachess without it (the lock asked nowhere); rows the lookahead alone adds
//   rows <csv>         per row: gameId, then 1 if the flag-fall verdict with the patch is a draw
//   time <fens> [n]    ns per call of Lock.cannotBeMated (the side to move flagged)
import chess.*
import chess.format.Fen
import chess.mateless.Lock
import scala.io.Source

def read(fen: String): Option[Position] = Fen.readWithMoveNumber(chess.variant.Standard, Fen.Full(fen)).map(_.position)
def lines(path: String): Vector[String] =
  val s = Source.fromFile(path, "UTF-8")
  try s.getLines().toVector
  finally s.close()

@main def lockcheck(command: String, path: String, rest: String*): Unit =
  command match
    case "locks" =>
      val out = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.OutputStreamWriter(System.out), 1 << 16))
      for f <- lines(path) if f.nonEmpty do
        read(f) match
          case None => out.println("err")
          case Some(p) =>
            out.println(s"${if Lock.cannotBeMated(p, Color.White) then 1 else 0}\t${if Lock.cannotBeMated(p, Color.Black) then 1 else 0}")
      out.flush()
    case "coverage" =>
      val rows = lines(path).drop(1).filter(_.nonEmpty)
      val ps = rows.flatMap(l => read(l.split(',')(12).trim))
      val now = ps.count(_.opponentHasInsufficientMaterial)
      val root = ps.count(p => Lock.cannotBeMated(p, p.color))
      val lookOnly = rows.filter(l => read(l.split(',')(12).trim).exists(p => p.opponentHasInsufficientMaterial && !Lock.cannotBeMated(p, p.color)))
      println(s"rows ${ps.length}: draws with the patch $now; lock at the root $root")
      for l <- lookOnly.take(400) do
        val p = read(l.split(',')(12).trim).get
        if !chess.variant.Standard.opponentHasInsufficientMaterial(p) then println("  " + l.split(',')(12).trim)
    case "rows" =>
      val out = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.OutputStreamWriter(System.out), 1 << 16))
      for l <- lines(path).drop(1) if l.nonEmpty do
        val c = l.split(',')
        out.println(s"${c(0)}\t${read(c(12).trim).fold("err")(p => if p.opponentHasInsufficientMaterial then "1" else "0")}")
      out.flush()
    case "time" =>
      val fens = lines(path).filter(_.nonEmpty)
      val rounds = rest.headOption.fold(9)(_.toInt)
      for r <- 1 to rounds do
        val ps = fens.flatMap(read).toArray
        ps.foreach(_.enPassantSquare)
        val t0 = System.nanoTime
        var n = 0
        var i = 0
        while i < ps.length do
          if Lock.cannotBeMated(ps(i), ps(i).color) then n += 1
          i += 1
        println(f"round $r: ${(System.nanoTime - t0).toDouble / ps.length}%.1f ns/call over ${ps.length}, $n locks")
