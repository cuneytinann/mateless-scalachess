package chess
package mateless

import scala.collection.mutable

/** Why a Domino search failed. */
enum Failure:
  case None, Mate, Horizon, Budget

final case class SearchResult(proven: Boolean, failure: Failure, budgetLeft: Long)

/** Rung 3: a short helpmate search, 6 plies and 100 nodes by default, without move-count rules.
  *
  * It looks for a series of legal moves, by both sides, that mates the flagged king. None within
  * the ply ceiling, every line ending in a position the static tests prove, a stalemate, or a
  * position met before: the flagged side can never be mated. A line that reaches the ceiling, or a
  * search that spends its budget, is a failure, never a proof. The 50- and 75-move rules and
  * repetitions play no part: they only cut lines short, so leaving them out can cost a proof but
  * never make a false one.
  *
  * After a move it asks only the static tests that move can have changed (Static.testsAfter), and
  * only while no en passant square stands. The budget pays for every move played, each promotion
  * separately. Moves are tried in Mateless's order (from square, then target square, castling as
  * the king to its rook's square, promotions queen, rook, bishop, knight), so a search here plays
  * the same moves and spends the same budget as Mateless's JavaScript and Rust searches.
  *
  * Not thread-safe: one instance per thread.
  */
final class Domino:

  private val seen = mutable.HashMap.empty[Domino.Key, Int]
  private var flagged: Color = Color.White
  private var budget = 0L
  private var failure = Failure.None
  private val budgets = new Array[Long](2)
  private val failures = Array(Failure.None, Failure.None)
  private var dead = 0

  /** The search for `flagged`; rootStatic: false when the caller has already asked the static
    * tests at the root.
    */
  def run(root: Position, flagged: Color, maxPly: Int, budget: Long, rootStatic: Boolean): SearchResult =
    seen.clear()
    this.flagged = flagged
    this.budget = budget
    failure = Failure.None
    val proven = node(root, maxPly, if rootStatic then TestsBoth else 0)
    SearchResult(proven, if proven then Failure.None else failure, math.max(this.budget, 0L))

  private def node(pos: Position, depth: Int, tests: Int): Boolean =
    if seen.put(Domino.key(pos), 3).isDefined then true // met before: covered there
    else if tests != 0 && Static.tests(pos, flagged, tests) then true
    else if depth == 0 then
      if !Domino.hasLegalMove(pos) then noLegalMove(pos)
      else
        failure = Failure.Horizon
        false
    else
      // One piece at a time: most searches fail on their first line, and then the other pieces'
      // moves are never generated.
      val checked = pos.check.yes && Static.pawnCheck(pos)
      val epBefore = pos.enPassantSquare.isDefined
      var own = pos.us.value
      var any = false
      var ok = true
      while ok && own != 0 do
        val moves = Domino.movesAt(pos, java.lang.Long.numberOfTrailingZeros(own))
        own &= own - 1
        if moves.length > 0 then any = true
        var i = 0
        while ok && i < moves.length do
          val m = moves(i)
          val tests = Static.testsAfter(m.captures, m.promotes, m.piece.role == Pawn, epBefore, checked)
          val b = budget
          budget = b - 1
          if b <= 0 then
            failure = Failure.Budget
            ok = false
          else
            val child = Domino.play(m)
            ok = node(child, depth - 1, if child.enPassantSquare.isEmpty then tests else 0)
          i += 1
      ok && (any || noLegalMove(pos))

  /** A position without legal moves ends its line; it fails only if the flagged king is mated. */
  private def noLegalMove(pos: Position): Boolean =
    if pos.color == flagged && pos.check.yes then
      failure = Failure.Mate
      false
    else true

  /** Both searches at once: for each side in `ask` (Flagged* bits), the same verdict, failure and
    * budget left as `run` with that side flagged. Index 0 answers for White flagged, 1 for Black.
    * One walk serves both: each node carries the sides still searching it, each side pays its own
    * budget and keeps its own record of the positions it has met, and a side that fails stops.
    */
  def run2(
      root: Position,
      ask0: Int,
      maxPly: Int,
      budget: Long,
      rootStatic: Boolean
  ): (SearchResult, SearchResult) =
    val ask = ask0 & (FlaggedWhite | FlaggedBlack)
    val idle = SearchResult(false, Failure.None, budget)
    if ask == FlaggedWhite then (run(root, Color.White, maxPly, budget, rootStatic), idle)
    else if ask == FlaggedBlack then (idle, run(root, Color.Black, maxPly, budget, rootStatic))
    else
      seen.clear()
      budgets(0) = budget
      budgets(1) = budget
      failures(0) = Failure.None
      failures(1) = Failure.None
      dead = 0
      if ask != 0 then node2(root, maxPly, ask, if rootStatic then TestsBoth else 0)
      def result(i: Int) =
        val proven = (ask & (1 << i)) != 0 && (dead & (1 << i)) == 0
        SearchResult(proven, if proven then Failure.None else failures(i), math.max(budgets(i), 0L))
      (result(0), result(1))

  private def node2(pos: Position, depth: Int, live0: Int, tests: Int): Unit =
    val k = Domino.key(pos)
    val had = seen.getOrElse(k, 0)
    var live = live0 & ~had // a side that met it before: covered there
    if live != 0 then
      seen.update(k, had | live)
      if tests != 0 then live &= ~Static.tests2(pos, live, tests)
      if live != 0 then
        if depth == 0 then
          if !Domino.hasLegalMove(pos) then noLegalMove2(pos, live) else fail(live, Failure.Horizon)
        else
          val checked = pos.check.yes && Static.pawnCheck(pos)
          val epBefore = pos.enPassantSquare.isDefined
          var own = pos.us.value
          var any = false
          while live != 0 && own != 0 do
            val moves = Domino.movesAt(pos, java.lang.Long.numberOfTrailingZeros(own))
            own &= own - 1
            if moves.length > 0 then any = true
            var i = 0
            while live != 0 && i < moves.length do
              val m = moves(i)
              val tests = Static.testsAfter(m.captures, m.promotes, m.piece.role == Pawn, epBefore, checked)
              var spent = 0
              if (live & FlaggedWhite) != 0 then
                val b = budgets(0)
                budgets(0) = b - 1
                if b <= 0 then spent |= FlaggedWhite
              if (live & FlaggedBlack) != 0 then
                val b = budgets(1)
                budgets(1) = b - 1
                if b <= 0 then spent |= FlaggedBlack
              if spent != 0 then
                fail(spent, Failure.Budget)
                live &= ~spent
              if live != 0 then
                val child = Domino.play(m)
                node2(child, depth - 1, live, if child.enPassantSquare.isEmpty then tests else 0)
                live &= ~dead // a side that failed in there searches no more
              i += 1
          if live != 0 && !any then noLegalMove2(pos, live)

  /** run2's noLegalMove: a mate fails the search in which the mated side is flagged. */
  private def noLegalMove2(pos: Position, live: Int): Unit =
    val mated = flaggedBit(pos.color)
    if pos.check.yes && (live & mated) != 0 then fail(mated, Failure.Mate)

  /** The searches of the sides in `mask` fail, for `why`. */
  private def fail(mask: Int, why: Failure): Unit =
    val m = mask & ~dead
    if (m & FlaggedWhite) != 0 then failures(0) = why
    if (m & FlaggedBlack) != 0 then failures(1) = why
    dead |= m

object Domino:

  /** The search's defaults: ply ceiling and node budget. */
  val Ply = 6
  val Budget = 100L

  /** The exact identity of a position: pieces, side to move, castling rights, en passant square
    * (only when a legal capture onto it exists). Not a hash: two positions are met as one only when
    * they are the same.
    */
  final case class Key(
      white: Long,
      pawns: Long,
      knights: Long,
      bishops: Long,
      rooks: Long,
      queens: Long,
      kings: Long,
      castles: Long,
      epAndSide: Int
  )

  def key(pos: Position): Key =
    val b = pos.board
    val ep = pos.enPassantSquare.fold(64)(_.value)
    Key(
      b.white.value,
      b.pawns.value,
      b.knights.value,
      b.bishops.value,
      b.rooks.value,
      b.queens.value,
      b.kings.value,
      pos.history.castles.value,
      ep * 2 + (if pos.color.white then 1 else 0)
    )

  private def promotionIndex(m: Move): Int = m.promotion match
    case Some(Queen) => 0
    case Some(Rook) => 1
    case Some(Bishop) => 2
    case Some(Knight) => 3
    case _ => 0

  /** The position after `m`, for the search: Move.after without the repetition history (its
    * Zobrist hashes), which the search does not read. The board, the side to move, the last move
    * (for en passant) and the castling rights are those of Move.after.
    */
  def play(m: Move): Position =
    val a = m.afterWithoutHistory
    var castles = a.history.castles
    var unmoved = a.history.unmovedRooks
    if m.captures then
      unmoved.side(m.dest) match
        case Some(side) =>
          unmoved = unmoved & ~m.dest.bl
          castles = side.fold(castles.without(!m.color))(castles.without(!m.color, _))
        case None =>
    if m.piece.is(Rook) && unmoved.contains(m.orig) then
      unmoved.side(m.orig) match
        case Some(side) =>
          unmoved = unmoved & ~m.orig.bl
          castles = side.fold(castles.without(m.color))(castles.without(m.color, _))
        case None =>
    else if m.piece.is(King) then
      unmoved = unmoved.without(m.color)
      castles = castles.without(m.color)
    a.copy(
      color = !m.color,
      history = a.history.copy(
        lastMove = Some(m.toUci),
        halfMoveClock = if m.piece.is(Pawn) || m.captures || m.promotes then HalfMoveClock.initial
        else a.history.halfMoveClock.incr,
        castles = castles,
        unmovedRooks = unmoved
      )
    )

  private val byTarget: Ordering[Move] = Ordering.by((m: Move) => m.dest.value * 8 + promotionIndex(m))

  /** The legal moves of the piece on `from`, in Mateless's order: by target (a castle as the king
    * to its rook's square, given once), then promotion (queen, rook, bishop, knight).
    */
  def movesAt(pos: Position, from: Int): Array[Move] =
    val moves =
      pos.generateMovesAt(Square.unsafe(from)).iterator.filter(m => m.castle.forall(_.rook == m.dest)).toArray
    if moves.length > 1 then java.util.Arrays.sort(moves, byTarget)
    moves

  /** Does the side to move have a legal move? The king first: it usually has one. */
  def hasLegalMove(pos: Position): Boolean =
    pos.ourKing.exists(k => pos.generateMovesAt(k).nonEmpty) || {
      var own = (pos.us & ~pos.board.kings).value
      var found = false
      while !found && own != 0 do
        found = pos.generateMovesAt(Square.unsafe(java.lang.Long.numberOfTrailingZeros(own))).nonEmpty
        own &= own - 1
      found
    }
