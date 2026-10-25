package chess
package mateless

/* Mateless's first two rungs: the material test and the lock detector, and the two together
   ("the static tests"). Both answer, for one position and one side, "can this side never be
   mated?", from the bitboards alone: they generate no move and search nothing.

   Squares are numbered as in scalachess (a1 = 0, b1 = 1, ..., h8 = 63), so the sets below are the
   raw Longs of chess.Bitboard. "Flagged" is the side to be proven unable to be mated: in a game,
   the side whose flag may fall, whose opponent must be shown unable to mate. */

/** Bits naming the flagged side, for the functions that answer for both sides at once. */
inline val FlaggedWhite = 1
inline val FlaggedBlack = 2

private[mateless] inline def flaggedBit(c: Color): Int = if c.white then FlaggedWhite else FlaggedBlack

/** Bits naming the static tests, for the callers that ask only those a move can have changed. */
inline val TestMaterial = 1
inline val TestLock = 2
inline val TestsBoth = 3

private[mateless] object Bits:
  inline val FileA = 0x0101010101010101L
  inline val FileH = 0x8080808080808080L
  inline val Rank1 = 0xffL
  inline val Rank8 = 0xff00000000000000L
  inline val Dark = 0xaa55aa55aa55aa55L

  inline def north(w: Long): Long = w << 8
  inline def south(w: Long): Long = w >>> 8

  /** One file east and west. */
  inline def sideways(w: Long): Long = ((w << 1) & ~FileA) | ((w >>> 1) & ~FileH)

  /** The squares of the set and their eight neighbours. */
  inline def zone(w: Long): Long =
    val v = w | (w << 8) | (w >>> 8)
    v | sideways(v)

  /** The four diagonal neighbours of every square of the set. */
  inline def diagonal(w: Long): Long = sideways((w << 8) | (w >>> 8))

  /** Occluded fills: every square reachable from g by steps north (south) through squares of p. */
  inline def fillNorth(g0: Long, p0: Long): Long =
    var g = g0
    var p = p0
    g |= p & (g << 8)
    p &= p << 8
    g |= p & (g << 16)
    p &= p << 16
    g | (p & (g << 32))
  inline def fillSouth(g0: Long, p0: Long): Long =
    var g = g0
    var p = p0
    g |= p & (g >>> 8)
    p &= p >>> 8
    g |= p & (g >>> 16)
    p &= p >>> 16
    g | (p & (g >>> 32))
  inline def count(w: Long): Int = java.lang.Long.bitCount(w)

import Bits.*

/** Rung 1: the flagged side can never be mated because the other side lacks the material to mate
  * at all.
  */
object Material:

  def cannotBeMated(board: Board, flagged: Color): Boolean =
    val m = board.byColor(!flagged).value
    val f = board.byColor(flagged).value
    if ((board.rooks.value | board.queens.value) & m) != 0 then false
    else
      val n = board.knights.value & m
      val bi = board.bishops.value & m
      if (n | bi) == 0 then (board.pawns.value & m) == 0
      else if board.pawns.value != 0 then false
      else
        val knights = board.knights.value & f
        val fb = board.bishops.value & f
        if n != 0 then bi == 0 && count(n) == 1 && (knights | fb | (board.rooks.value & f)) == 0
        else
          val dark = (bi & Dark) != 0
          val light = (bi & ~Dark) != 0
          !(dark && light) && (knights | (fb & (if dark then ~Dark else Dark))) == 0

/** Rung 2: pawns, kings and bishops form a structure that no series of legal moves can open.
  *
  * Each king's reach (the squares it may ever stand on) is grown from its square, never onto a
  * square a pawn that can never move attacks; the pawns that can never move shrink as the kings
  * may reach them; and so on to a fixed point. The structure leaks if a pawn can promote or
  * capture, a frozen king can move, a bishop can reach a pawn's path, or the flagged king can reach
  * a square the other side attacks where it has no guaranteed flight. Tried with both kings free,
  * then with each king frozen on its square. A knight, rook or queen on the board, or an en passant
  * square, fails it at once.
  */
object Lock:

  private final class Census(
      val wp: Long,
      val bp: Long,
      val wk: Long,
      val bk: Long,
      val wb: Long,
      val bb: Long
  )

  private final class Sets(
      val wCone: Long,
      val bCone: Long,
      val wKing: Long,
      val bKing: Long,
      val wBish: Long,
      val bBish: Long
  )

  private inline def twinBishops(b: Long): Boolean = count(b & Dark) > 1 || count(b & ~Dark) > 1

  /** Both kings' reaches grown at once against their masks. */
  private def kingFill2(w0: Long, mw: Long, b0: Long, mb: Long): (Long, Long) =
    val mwa = mw & ~FileA
    val mwh = mw & ~FileH
    val mba = mb & ~FileA
    val mbh = mb & ~FileH
    var w = w0
    var b = b0
    var done = false
    while !done do
      val v = w | (w << 8) | (w >>> 8)
      val a = w | (v & mw) | ((v << 1) & mwa) | ((v >>> 1) & mwh)
      val u = b | (b << 8) | (b >>> 8)
      val d = b | (u & mb) | ((u << 1) & mba) | ((u >>> 1) & mbh)
      if a == w && d == b then done = true
      else
        w = a
        b = d
    (w, b)

  /** A level up to the mating net, with king `fr` frozen (0: none): null when the structure leaks
    * whichever side is flagged, otherwise the sets the net reads.
    */
  private def level(c: Census, fr: Long): Sets | Null =
    val frozenWhite = (fr & c.wk) != 0
    val frozenBlack = !frozenWhite && fr != 0
    val nearFrozenBlack = if frozenBlack then zone(c.bk) else 0L
    val nearFrozenWhite = if frozenWhite then zone(c.wk) else 0L
    // Quick reject with the first walls (the capture squares are only the kings' squares yet).
    val wc0 = fillNorth(c.wp, ~((c.bp & ~c.wk) | fr))
    val bc0 = fillSouth(c.bp, ~((c.wp & ~c.bk) | fr))
    if ((wc0 & Rank8) | (bc0 & Rank1)) != 0 || (north(sideways(wc0)) & bc0) != 0 then null
    else
      var wFixed = c.wp
      var bFixed = c.bp
      var wKing = c.wk
      var bKing = c.bk
      var wTake = c.wk
      var bTake = c.bk
      var wWall = -1L
      var bWall = -1L
      var leak = false
      var done = false
      while !done do
        val pw = wWall
        val pb = bWall
        wWall = (c.wp & ~bTake) | fr
        bWall = (c.bp & ~wTake) | fr
        val nwFixed = wFixed & wWall & south(bWall | wFixed)
        val nbFixed = bFixed & bWall & north(wWall | bFixed)
        if wWall == pw && bWall == pb && nwFixed == wFixed && nbFixed == bFixed then done = true
        else
          val mw = ~(nwFixed | sideways(south(nbFixed)) | nearFrozenBlack)
          val mb = ~(nbFixed | sideways(north(nwFixed)) | nearFrozenWhite)
          val (nwKing, nbKing) = kingFill2(wKing, mw, bKing, mb)
          if (frozenWhite && nwKing != c.wk) || (frozenBlack && nbKing != c.bk) then
            leak = true // the frozen king can move
            done = true
          else
            val tb = bTake | nbKing
            val nbTake = tb | south(fillSouth(tb & ~bWall, ~bWall))
            val tw = wTake | nwKing
            val nwTake = tw | north(fillNorth(tw & ~wWall, ~wWall))
            val changed = (nwFixed ^ wFixed) | (nbFixed ^ bFixed) | (nwKing ^ wKing) | (nbKing ^ bKing) |
              (nbTake ^ bTake) | (nwTake ^ wTake)
            wFixed = nwFixed
            bFixed = nbFixed
            wKing = nwKing
            bKing = nbKing
            wTake = nwTake
            bTake = nbTake
            if changed == 0 then done = true
      if leak then null
      else
        val wCone = fillNorth(c.wp, ~bWall)
        val bCone = fillSouth(c.bp, ~wWall)
        if ((wCone & Rank8) | (bCone & Rank1)) != 0 then null // a pawn can promote
        else if (north(sideways(wCone)) & bCone) != 0 then null // a pawn can capture
        else
          var wBish = c.wb
          var bBish = c.bb
          if (wBish | bBish) != 0 then
            val m = ~(wFixed | bFixed)
            var stable = false
            while !stable do
              val a = wBish | (diagonal(wBish) & m)
              val d = bBish | (diagonal(bBish) & m)
              if a == wBish && d == bBish then stable = true
              else
                wBish = a
                bBish = d
          if (diagonal(wBish) & bCone) != 0 || (diagonal(bBish) & wCone) != 0 then
            null // a bishop meets a pawn's path
          else Sets(wCone, bCone, wKing, bKing, wBish, bBish)

  /** The mating net: can the flagged king be caught by the pawns and bishops of the other side? */
  private def netLeaks(s: Sets, flagged: Color): Boolean =
    val (attack, reach, free, spare) =
      if flagged.white then
        val attack = diagonal(s.bBish) | sideways(south(s.bCone))
        val free = ~(s.wCone | zone(s.bKing) | attack)
        (attack, s.wKing, free, free & ~s.wBish)
      else
        val attack = diagonal(s.wBish) | sideways(north(s.wCone))
        val free = ~(s.bCone | zone(s.wKing) | attack)
        (attack, s.bKing, free, free & ~s.bBish)
    val flight =
      sideways(spare) | north(spare) | south(spare) | ((north(free) | south(free)) & sideways(free))
    (attack & reach & ~flight) != 0

  /** Of the sides in `ask`, those the level with king `fr` frozen leaks to. */
  private def leaks2(c: Census, fr: Long, ask: Int): Int =
    val s = level(c, fr)
    if s == null then ask
    else
      (if (ask & FlaggedWhite) != 0 && netLeaks(s, Color.White) then FlaggedWhite else 0) |
        (if (ask & FlaggedBlack) != 0 && netLeaks(s, Color.Black) then FlaggedBlack else 0)

  private def census(pos: Position): Census | Null =
    val b = pos.board
    if (b.knights.value | b.rooks.value | b.queens.value) != 0 || pos.enPassantSquare.isDefined then null
    else
      val wb = (b.bishops & b.white).value
      val bb = (b.bishops & b.black).value
      if twinBishops(wb) || twinBishops(bb) then null
      else
        Census(
          (b.pawns & b.white).value,
          (b.pawns & b.black).value,
          (b.kings & b.white).value,
          (b.kings & b.black).value,
          wb,
          bb
        )

  /** Can `flagged` never be mated, by the lock detector? */
  def cannotBeMated(pos: Position, flagged: Color): Boolean = proven2(pos, flaggedBit(flagged)) != 0

  /** The lock detector for both sides at once: of the sides in `ask` (Flagged* bits), those it
    * proves unable to be mated. A level is computed once for both sides; a side is proven at the
    * first level that does not leak to it.
    */
  def proven2(pos: Position, ask: Int): Int =
    val c = census(pos)
    if c == null then 0
    else
      var open = leaks2(c, 0L, ask)
      if open == 0 then ask
      else
        val held = c.wp | c.bp | sideways(north(c.wp) | south(c.bp))
        var i = 0
        while i < 2 && open != 0 do
          val king = if i == 0 then c.wk else c.bk
          if (zone(king) & ~king & ~held) == 0 then open = leaks2(c, king, open)
          i += 1
        ask & ~open

/** The two static tests together. */
object Static:

  /** Is the side to move in check by a pawn? */
  def pawnCheck(pos: Position): Boolean =
    val b = pos.board
    if pos.color.white then (sideways(north((b.kings & b.white).value)) & (b.pawns & b.black).value) != 0
    else (sideways(south((b.kings & b.black).value)) & (b.pawns & b.white).value) != 0

  /** The static tests whose answer a move can change: the material test only by a capture or a
    * promotion; the lock detector by a capture, a pawn move, a move made while an en passant
    * square stood (the right lapses), or a step out of a pawn's check (a king in a pawn's check
    * stands on a square its reach leaves out).
    */
  inline def testsAfter(
      capture: Boolean,
      promotion: Boolean,
      pawn: Boolean,
      epBefore: Boolean,
      pawnCheck: Boolean
  ): Int =
    (if capture || promotion then TestMaterial
     else 0) | (if capture || pawn || epBefore || pawnCheck then TestLock else 0)

  /** The static tests in `tests` for `flagged`. */
  def tests(pos: Position, flagged: Color, tests: Int): Boolean =
    ((tests & TestMaterial) != 0 && Material.cannotBeMated(pos.board, flagged)) ||
      ((tests & TestLock) != 0 && Lock.cannotBeMated(pos, flagged))

  /** The static tests in `tests` for the sides in `ask`: the sides proven. */
  def tests2(pos: Position, ask: Int, tests: Int): Int =
    var proven = 0
    if (tests & TestMaterial) != 0 then
      if (ask & FlaggedWhite) != 0 && Material.cannotBeMated(pos.board, Color.White) then
        proven |= FlaggedWhite
      if (ask & FlaggedBlack) != 0 && Material.cannotBeMated(pos.board, Color.Black) then
        proven |= FlaggedBlack
    val rest = ask & ~proven
    if rest != 0 && (tests & TestLock) != 0 then proven | Lock.proven2(pos, rest) else proven

  /** Can `flagged` never be mated, by either static test? */
  def cannotBeMated(pos: Position, flagged: Color): Boolean = tests(pos, flagged, TestsBoth)

  /** Both static tests for the sides in `ask`: the sides proven. */
  def cannotBeMated2(pos: Position, ask: Int): Int = tests2(pos, ask, TestsBoth)
