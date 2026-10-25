// MatelessTest's assertions, run here (munit is not available in this environment)
import chess.*
import chess.format.Fen
import chess.mateless.Flags
import chess.variant.Standard
def pos(fen: String): Position = Fen.readWithMoveNumber(Standard, Fen.Full(fen)).map(_.position).get
@main def cases2(): Unit =
  var bad = 0
  def check(name: String, ok: Boolean): Unit = { if !ok then bad += 1; println(s"${if ok then "ok  " else "FAIL"} $name") }
  for fen <- List("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3K4 b - - 47 92", "3k4/8/3p4/2pP1p1p/p1P2PpP/P2K2P1/8/8 b - - 4 56",
      "8/8/4k3/p2p2p1/P2P2P1/2K5/8/8 w - - 51 75", "7k/8/6Q1/6Pp/p7/R2P2KP/8/8 b - - 0 56",
      "8/8/8/6p1/8/2B2KP1/kP6/3Q4 b - - 4 45", "r4b2/p7/4R2p/kpp5/2P5/KPB3P1/3rBP1P/8 b - - 1 33") do
    val p = pos(fen)
    check(s"never mate $fen (scalachess today: ${chess.variant.Standard.opponentHasInsufficientMaterial(p)})", p.opponentCanNeverMate && p.opponentHasInsufficientMaterial)
  check("can still mate", !pos("2kQ4/8/8/2K5/8/8/8/8 b - - 0 1").opponentCanNeverMate)
  check("stalemate", !pos("k7/2Q5/8/2K5/8/8/8/8 b - - 0 1").opponentCanNeverMate)
  val f = Flags(); f.update(pos("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3K4 b - - 47 92"), 0); check("flags dead", f.dead)
  val g = Flags(); g.update(pos("4k3/8/8/8/8/8/3q4/4K3 w - - 0 1"), 0); val a0 = g.neverMates(Color.White) && !g.neverMates(Color.Black)
  g.update(pos("4k3/8/8/8/8/8/3K4/8 b - - 0 1"), 1); val d = g.dead; g.rewind(0)
  check("rewind", a0 && d && g.neverMates(Color.White) && !g.neverMates(Color.Black))
  println(s"$bad failures")
