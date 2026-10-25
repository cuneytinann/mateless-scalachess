package chess
package mateless

/** A proof that a side can never be mated, or the failure to find one. */
final case class Proof(proven: Boolean, staticProof: Boolean, failure: Failure, nodes: Long)

/** The ladder: the material test, the lock detector, then the Domino search, for one position
  * (proofs, dead positions, the verdict when a flag falls) and for a game played move by move
  * (Flags). Standard chess only.
  */
object Ladder:

  private val staticProof = Proof(true, true, Failure.None, 0)

  private def proofOf(r: SearchResult, budget: Long) =
    Proof(r.proven, false, r.failure, budget - r.budgetLeft)

  /** Can `flagged` never be mated? The static tests, then the Domino search. */
  def proveCannotBeMated(
      search: Domino,
      pos: Position,
      flagged: Color,
      maxPly: Int = Domino.Ply,
      budget: Long = Domino.Budget
  ): Proof =
    if Static.cannotBeMated(pos, flagged) then staticProof
    else proofOf(search.run(pos, flagged, maxPly, budget, false), budget)

  /** proveCannotBeMated for both sides in one pass: (White flagged, Black flagged). */
  def proveBoth(
      search: Domino,
      pos: Position,
      maxPly: Int = Domino.Ply,
      budget: Long = Domino.Budget
  ): (Proof, Proof) =
    val both = FlaggedWhite | FlaggedBlack
    val st = Static.cannotBeMated2(pos, both)
    val (w, b) =
      if st != both then search.run2(pos, both & ~st, maxPly, budget, false)
      else (SearchResult(false, Failure.None, budget), SearchResult(false, Failure.None, budget))
    (
      if (st & FlaggedWhite) != 0 then staticProof else proofOf(w, budget),
      if (st & FlaggedBlack) != 0 then staticProof else proofOf(b, budget)
    )

  /** Is the position dead (FIDE 5.2.2): can neither side ever be mated? */
  def isDead(search: Domino, pos: Position): Boolean =
    val (w, b) = proveBoth(search, pos)
    w.proven && b.proven

  /** When `side`'s flag falls (or it resigns) in `pos`: can its opponent never mate? Then the game
    * is drawn (FIDE 6.9, 5.1.2).
    */
  def opponentCannotMate(search: Domino, pos: Position, side: Color): Boolean =
    proveCannotBeMated(search, pos, side).proven

/** The two flags of a game played move by move: a side whose flag is down has been proven unable
  * to mate for the rest of the game, and is never examined again.
  *
  * At each ply: the static tests, when the move just played can have changed them (both after a
  * capture or a pawn move, the lock detector also after a move made while an en passant square
  * stood or out of a pawn's check; neither while an en passant square stands; both at the first
  * update), then the Domino search, both sides in one pass. Both flags down is a dead position.
  * When a flag falls, the verdict is read from the opponent's flag: down, a draw; up, a loss.
  *
  * A platform that lets moves be taken back calls rewind(ply) with the ply the game is back at.
  */
final class Flags:

  /** The ply at which each side was proven unable to mate; -1 while its flag is up. */
  var whiteDownAt: Int = -1
  var blackDownAt: Int = -1

  /** The ply whose position the flags were last brought up to date for; -1 before the first. */
  private var updatedPly = -1

  /** Of the position of ply memoryPly (the last update's): did an en passant square stand, was the
    * side to move in a pawn's check? Used by the update for the next ply only.
    */
  private var memoryPly = -1
  private var updatedEp = false
  private var updatedCheck = false
  private val search = Domino()

  /** Has `color` been proven unable to mate, for the rest of the game? */
  def neverMates(color: Color): Boolean = (if color.white then whiteDownAt else blackDownAt) >= 0

  /** Climbs the ladder for each side whose flag is still up, at `pos`, the position of game ply
    * `ply`, once per ply.
    */
  def update(pos: Position, ply: Int): Unit =
    if updatedPly != ply then
      val first = updatedPly < 0
      val next = ply == updatedPly + 1 && memoryPly == updatedPly
      val epBefore = updatedEp
      val checkBefore = updatedCheck
      val ep = pos.enPassantSquare.isDefined
      val halfmove = pos.history.halfMoveClock.value
      updatedPly = ply
      memoryPly = ply
      updatedEp = ep
      // Only a pawn's check matters, and it comes only from a pawn move.
      updatedCheck = halfmove == 0 && !ep && Static.pawnCheck(pos)
      val tests =
        if first then TestsBoth
        else if ep then 0
        else if !next then (if halfmove <= 1 then TestsBoth else 0) // after plies left out or a rewind
        else (if halfmove == 0 then TestsBoth else 0) | (if epBefore || checkBefore then TestLock else 0)
      // White's flag (may White still mate?) is the proof with Black flagged, and the other way round.
      val open = (if neverMates(Color.White) then 0 else FlaggedBlack) | (if neverMates(Color.Black) then 0
                                                                          else FlaggedWhite)
      if open != 0 then
        var proven = if tests != 0 then Static.tests2(pos, open, tests) else 0
        if (open & ~proven) != 0 then
          val (w, b) = search.run2(pos, open & ~proven, Domino.Ply, Domino.Budget, false)
          if w.proven then proven |= FlaggedWhite
          if b.proven then proven |= FlaggedBlack
        if (proven & FlaggedBlack) != 0 then whiteDownAt = ply
        if (proven & FlaggedWhite) != 0 then blackDownAt = ply

  /** The game is back at ply `ply` (moves taken back): a flag that came down after it goes up
    * again, and the next update is for ply + 1.
    */
  def rewind(ply: Int): Unit =
    if whiteDownAt > ply then whiteDownAt = -1
    if blackDownAt > ply then blackDownAt = -1
    if updatedPly > ply then updatedPly = ply

  /** Both flags down: a dead position. */
  def dead: Boolean = whiteDownAt >= 0 && blackDownAt >= 0
