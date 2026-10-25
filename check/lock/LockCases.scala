// The lock-only patch against scalachess's own standard-chess expectations on
// opponentHasInsufficientMaterial (AutodrawTest, VariantTest) and LockTest's assertions (munit is
// not available here).
import chess.*
import chess.format.Fen
import chess.mateless.Lock
import chess.variant.Standard

def positionAt(fen: String, move: Option[(Square, Square)]): Position =
  val p = Fen.readWithMoveNumber(Standard, Fen.Full(fen)).get.position
  move.fold(p)((f, t) => p.move(f, t, None).toOption.get.after)

@main def lockcases(): Unit =
  import Square.*
  var bad = 0
  def check(name: String, ok: Boolean): Unit =
    if !ok then bad += 1
    println(s"${if ok then "ok  " else "FAIL"} $name")
  val testKit: List[(String, String, Option[(Square, Square)], Boolean)] = List(
    ("two knights", "1n2k1n1/8/8/8/8/8/8/4K3 w - - 0 1", None, false),
    ("knight versus pawn", "7K/5k2/7P/6n1/8/8/8/8 b - - 0 40", Some((F7, F8)), false),
    ("bishops versus pawn", "1b1b3K/8/5k1P/8/8/8/8/8 b - - 0 40", Some((B8, E5)), false),
    ("bishops versus queen (1)", "b2b3K/8/5k1Q/8/8/8/8/8 b - -", Some((F6, E5)), false),
    ("bishops versus queen (2)", "1b1b3K/8/5k1Q/8/8/8/8/8 b - -", Some((F6, E5)), true),
    ("knight versus pawns", "8/8/5N2/8/6p1/8/5K1p/7k w - - 0 37", Some((F6, E4)), false),
    ("knight versus pieces", "8/8/8/4N3/4k1p1/6K1/8/3b4 w - - 5 59", Some((E5, F7)), false),
    ("opposite bishops with queen", "8/8/3Q4/2bK4/B7/8/8/k7 b - - 0 67", Some((A1, B2)), false),
    ("same-color bishops on both sides", "5K2/8/8/1B6/8/k7/6b1/8 w - - 0 39", None, true),
    ("insufficient (bishop)", "krq5/bqqq4/qqr5/1qq5/8/8/8/3qB2K b - -", None, true),
    ("not insufficient (bishop)", "8/7B/K7/2b5/1k6/8/8/8 b - -", None, false),
    ("insufficient (knight)", "8/3k4/2q5/8/8/K1N5/8/8 b - -", None, true),
    ("only moves draw 1", "2kQ4/8/2K5/8/8/8/8/8 b - - 0 1", None, true),
    ("only moves draw 2", "2kQ4/8/2K5/2N5/8/8/8/8 b - - 0 1", None, true),
    ("only moves draw 3", "2kQ4/8/2K5/2B5/8/8/8/8 b - - 0 1", None, true),
    ("only moves draw 4", "1bkQ4/8/2K5/8/8/8/8/8 b - - 0 1", None, true),
    ("only moves draw 5", "n1kQ4/8/2K5/8/8/8/8/8 b - - 0 1", None, true),
    ("only moves draw 6", "5Q1k/4b3/7K/3r4/7n/8/7P/8 b - - 0 1", None, true),
    ("only moves draw 7", "7k/5Q1p/7P/8/8/8/1q4R1/K7 w - - 0 1", None, true),
    ("some move does not draw 1", "2kQ4/8/8/2K5/8/8/8/8 b - - 0 1", None, false),
    ("some move does not draw 2", "K2kr3/P3Q3/8/8/8/8/1r6/8 b - - 0 1", None, false),
    ("stalemate", "k7/2Q5/8/2K5/8/8/8/8 b - - 0 1", None, false)
  )
  for (name, fen, move, want) <- testKit do
    check(s"scalachess: $name", positionAt(fen, move).opponentHasInsufficientMaterial == want)
  for fen <- List("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3K4 b - - 47 92", "3k4/8/3p4/2pP1p1p/p1P2PpP/P2K2P1/8/8 b - - 4 56",
      "8/8/4k3/p2p2p1/P2P2P1/2K5/8/8 w - - 51 75") do
    val p = positionAt(fen, None)
    check(s"locked: $fen", Lock.cannotBeMated(p, p.color) && p.opponentHasInsufficientMaterial)
  for (name, fen) <- List(("pawn's check", "8/8/1k6/p2p2p1/PK1P2Pp/7P/8/8 w - - 0 49"),
      ("forced capture of a rook", "8/8/6R1/1p1p1k2/1P1Pp3/4P1p1/6P1/6K1 b - - 2 42")) do
    val p = positionAt(fen, None)
    check(s"one move away ($name)", !Lock.cannotBeMated(p, p.color) && p.opponentHasInsufficientMaterial)
  val knight = positionAt("8/8/4k3/3p2p1/1p1P1pP1/1P3P2/8/3KN3 b - - 47 92", None)
  check("not locked: a knight", !Lock.cannotBeMated(knight, knight.color) && !knight.opponentHasInsufficientMaterial)
  val capture = positionAt("8/8/4k3/3p2p1/1p1P1pP1/1P2P3/8/3K4 b - - 0 92", None)
  check("not locked: a pawn can capture", !Lock.cannotBeMated(capture, capture.color))
  println(s"$bad failures")
