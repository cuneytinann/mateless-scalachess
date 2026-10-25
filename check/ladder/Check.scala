// The same lines as Mateless's Rust tool (rust/src/bin/mateless.rs), from the scalachess port:
//   labels <positions.txt>   per labelled line and flag: line, flag, proven, static, failure, nodes
//   layers <csv>             per row: gameId, layer (material|lock|domino|none), nodes, failure
//   games <file>             per game (FENs joined by |): the plies at which the flags came down
//   time-games <file> [rounds] / time-layers <csv> [rounds]: the same work, timed (ns per item)
//   rows <csv>               per row: gameId, 1 if opponentHasInsufficientMaterial (the flag-fall verdict)
import chess.*
import chess.format.Fen
import chess.mateless.*
import java.io.{ BufferedWriter, OutputStreamWriter }
import scala.io.Source

// readWithMoveNumber keeps the halfmove clock (Fen.read drops it); the flags read it
def read(fen: String): Option[Position] = Fen.readWithMoveNumber(chess.variant.Standard, Fen.Full(fen)).map(_.position)

def failureName(f: Failure): String = f match
  case Failure.None => "-"
  case Failure.Mate => "mate"
  case Failure.Horizon => "horizon"
  case Failure.Budget => "budget"

def lines(path: String): Vector[String] =
  val s = Source.fromFile(path, "UTF-8")
  try s.getLines().toVector
  finally s.close()

@main def check(command: String, path: String, rest: String*): Unit =
  val out = BufferedWriter(OutputStreamWriter(System.out, "UTF-8"), 1 << 16)
  val search = Domino()
  command match
    case "labels" =>
      for (line, i) <- lines(path).zipWithIndex if line.length >= 4 && Set("WB ", "W- ", "-B ", "-- ")(line.take(3)) do
        read(line.drop(3).trim) match
          case None => out.write(s"$i\tfen-error\t?\n")
          case Some(pos) =>
            val (w, b) = Ladder.proveBoth(search, pos)
            for (flag, p) <- List(("w", w), ("b", b)) do
              out.write(s"$i\t$flag\t${if p.proven then 1 else 0}\t${if p.staticProof then 1 else 0}\t${failureName(p.failure)}\t${p.nodes}\n")
    case "layers" =>
      for line <- lines(path).drop(1) if line.nonEmpty do
        val cols = line.split(',')
        val (id, fen) = (cols(0), cols(12).trim)
        read(fen) match
          case None => out.write(s"$id\tfen-error\t?\t-\n")
          case Some(pos) =>
            val z = pos.color
            if Material.cannotBeMated(pos.board, z) then out.write(s"$id\tmaterial\t0\t-\n")
            else if Static.cannotBeMated(pos, z) then out.write(s"$id\tlock\t0\t-\n")
            else
              val p = Ladder.proveCannotBeMated(search, pos, z)
              out.write(s"$id\t${if p.proven then "domino" else "none"}\t${p.nodes}\t${failureName(p.failure)}\n")
    case "games" =>
      for line <- lines(path) if line.nonEmpty do
        val flags = Flags()
        for (fen, ply) <- line.split('|').zipWithIndex do flags.update(read(fen).get, ply)
        out.write(s"${flags.whiteDownAt}\t${flags.blackDownAt}\n")
    case "time-games" =>
      val rounds = rest.headOption.fold(3)(_.toInt)
      val games = lines(path).filter(_.nonEmpty).map(_.split('|').map(f => read(f).get))
      val plies = games.map(_.length).sum
      for r <- 1 to rounds do
        val t0 = System.nanoTime
        var sink = 0
        for g <- games do
          val flags = Flags()
          var ply = 0
          while ply < g.length do
            flags.update(g(ply), ply)
            ply += 1
          sink += flags.whiteDownAt
        out.write(f"round $r: ${(System.nanoTime - t0).toDouble / plies}%.0f ns/ply over $plies plies ($sink)\n")
        out.flush()
    case "time-layers" =>
      val rounds = rest.headOption.fold(3)(_.toInt)
      val ps = lines(path).drop(1).filter(_.nonEmpty).flatMap(l => read(l.split(',')(12).trim))
      for r <- 1 to rounds do
        val t0 = System.nanoTime
        var n = 0
        for p <- ps do if Ladder.opponentCannotMate(search, p, p.color) then n += 1
        out.write(f"round $r: ${(System.nanoTime - t0).toDouble / ps.length}%.0f ns/row over ${ps.length} rows, $n draws\n")
        out.flush()
    case "rows" =>
      // per row: gameId, then 1 if opponentHasInsufficientMaterial is true (the flag-fall verdict of
      // the scalachess built: today's in the ref build, with the ladder in the c build)
      for l <- lines(path).drop(1) if l.nonEmpty do
        val c = l.split(',')
        out.write(s"${c(0)}\t${read(c(12).trim).fold("err")(p => if p.opponentHasInsufficientMaterial then "1" else "0")}\n")
    case "scalachess" =>
      // today's scalachess at a flag fall (the side to move ran out of time): draw if
      // opponentHasInsufficientMaterial; against the ladder
      val rounds = rest.headOption.fold(3)(_.toInt)
      val ps = lines(path).drop(1).filter(_.nonEmpty).flatMap(l => read(l.split(',')(12).trim))
      val now = ps.count(_.opponentHasInsufficientMaterial)
      val ladder = ps.count(p => Ladder.opponentCannotMate(search, p, p.color))
      val both = ps.count(p => p.opponentHasInsufficientMaterial && Ladder.opponentCannotMate(search, p, p.color))
      out.write(s"rows ${ps.length}: scalachess draws $now, Mateless draws $ladder, both $both, scalachess only ${now - both}\n")
      for r <- 1 to rounds do
        val t0 = System.nanoTime
        val n = ps.count(_.opponentHasInsufficientMaterial)
        out.write(f"round $r: opponentHasInsufficientMaterial ${(System.nanoTime - t0).toDouble / ps.length}%.0f ns/row ($n)\n")
        out.flush()
    case "scalachess-rows" =>
      // per row: gameId, date, scalachess draws (0/1), Mateless draws (0/1), Mateless layer
      for line <- lines(path).drop(1) if line.nonEmpty do
        val cols = line.split(',')
        read(cols(12).trim).foreach: p =>
          val z = p.color
          val layer =
            if Material.cannotBeMated(p.board, z) then "material"
            else if Static.cannotBeMated(p, z) then "lock"
            else if Ladder.proveCannotBeMated(search, p, z).proven then "domino"
            else "none"
          out.write(s"${cols(0)}\t${cols(1)}\t${if p.opponentHasInsufficientMaterial then 1 else 0}\t${if layer != "none" then 1 else 0}\t$layer\n")
    case "takebacks" =>
      // a move taken back (Flags.rewind) and another line played: the flags must be those of flags
      // fed the game as it stands, from its start
      var n = 0
      var bad = 0
      for (line, gi) <- lines(path).zipWithIndex if line.nonEmpty do
        val before = line.split('|').map(f => read(f).get)
        if before.length >= 3 then
          val k = (gi * 7919) % (before.length - 1)
          var p = before(k)
          val after = Vector.newBuilder[Position]
          var ply = 0
          var going = true
          while going && ply < 12 do
            val moves = (0 until 64).iterator.flatMap(sq => Domino.movesAt(p, sq)).toVector
            if moves.isEmpty then going = false
            else
              p = moves((gi + ply * 3) % moves.length).after
              after += p
              ply += 1
          val tail = after.result()
          val a = Flags()
          before.indices.foreach(i => a.update(before(i), i))
          a.rewind(k)
          tail.indices.foreach(i => a.update(tail(i), k + 1 + i))
          val fresh = Flags()
          (before.take(k + 1) ++ tail).zipWithIndex.foreach((q, i) => fresh.update(q, i))
          n += 1
          if a.whiteDownAt != fresh.whiteDownAt || a.blackDownAt != fresh.blackDownAt then
            bad += 1
            if bad <= 5 then out.write(s"takeback differs in game $gi back to $k: ${a.whiteDownAt},${a.blackDownAt} against ${fresh.whiteDownAt},${fresh.blackDownAt}\n")
      out.write(s"$n takebacks: $bad problems\n")
    case "time-lock" =>
      // the bare lock detector, the side to move flagged: ns per call; positions read each round,
      // untimed, so the lazy en passant square is part of the call (as at a flag fall)
      val fens = lines(path).filter(_.nonEmpty)
      val rounds = rest.headOption.fold(5)(_.toInt)
      val cached = rest.lift(1).contains("cached")
      for r <- 1 to rounds do
        val ps = fens.flatMap(read).toArray
        if cached then ps.foreach(_.enPassantSquare) // computed before the timing: the detector alone
        val t0 = System.nanoTime
        var n = 0
        var i = 0
        while i < ps.length do
          if Lock.cannotBeMated(ps(i), ps(i).color) then n += 1
          i += 1
        out.write(f"round $r: ${(System.nanoTime - t0).toDouble / ps.length}%.1f ns/call over ${ps.length}, $n locks\n")
        out.flush()
    case "bare" =>
      // today's verdict (#821) with the bare lock detector added at the root and in the lookahead's
      // children, against today's and against the full ladder
      def lock(p: Position, flagged: Color) = Lock.cannotBeMated(p, flagged)
      def bare(p: Position): Boolean =
        p.opponentHasInsufficientMaterial || lock(p, p.color) || (p.legalMoves.nonEmpty &&
          p.legalMoves.forall { m =>
            val n = m.after
            val w = n.winner
            w != Some(!p.color) && (w == Some(p.color) || n.playerHasInsufficientMaterial || n.staleMate || lock(n, p.color))
          })
      def rootOnly(p: Position) = p.opponentHasInsufficientMaterial || lock(p, p.color)
      if rest.contains("list") then
        // the rows only the lock in the lookahead's children adds, with what the side to move can play
        for l <- lines(path).drop(1) if l.nonEmpty; p <- read(l.split(',')(12).trim) if bare(p) && !rootOnly(p) do
          val moves = p.legalMoves.filter(m => m.castle.forall(_.rook == m.dest)).map(_.toUci.uci).mkString(" ")
          out.write(s"${l.split(',')(0)}\t${l.split(',')(12).trim}\t${if p.check.yes then "check" else "-"}\t$moves\n")
        out.flush()
        System.exit(0)
      val ps = lines(path).drop(1).filter(_.nonEmpty).flatMap(l => read(l.split(',')(12).trim))
      val today = ps.count(_.opponentHasInsufficientMaterial)
      val root = ps.count(rootOnly)
      val withLook = ps.count(bare)
      val full = ps.count(p => Ladder.opponentCannotMate(search, p, p.color))
      val bareNotFull = ps.count(p => bare(p) && !Ladder.opponentCannotMate(search, p, p.color))
      out.write(s"rows ${ps.length}: today $today; + lock at the root $root; + lock in the lookahead $withLook; full ladder $full; bare but not the ladder $bareNotFull\n")
      val rounds = rest.headOption.fold(5)(_.toInt)
      for r <- 1 to rounds do
        val qs = lines(path).drop(1).filter(_.nonEmpty).flatMap(l => read(l.split(',')(12).trim)).toArray
        val t0 = System.nanoTime
        val a = qs.count(_.opponentHasInsufficientMaterial)
        val t1 = System.nanoTime
        val qs2 = lines(path).drop(1).filter(_.nonEmpty).flatMap(l => read(l.split(',')(12).trim)).toArray
        val t2 = System.nanoTime
        val b = qs2.count(bare)
        val t3 = System.nanoTime
        out.write(f"round $r: today ${(t1 - t0).toDouble / qs.length}%.0f ns/row ($a), bare ${(t3 - t2).toDouble / qs.length}%.0f ns/row ($b)\n")
        out.flush()
    case "material" =>
      // Mateless's material test against scalachess's, both sides, on every position of the file
      // (FENs, one per line): the counts where they differ
      var n = 0
      var onlyMateless = 0
      var onlyScalachess = 0
      var yes = 0
      for f <- lines(path) if f.nonEmpty; p <- read(f) do
        for flagged <- List(Color.White, Color.Black) do
          n += 1
          val m = Material.cannotBeMated(p.board, flagged)
          if m then yes += 1
          // scalachess: the side to move's opponent / the side to move has insufficient material
          val s = if flagged == p.color then p.variant.opponentHasInsufficientMaterial(p) else p.playerHasInsufficientMaterial
          if m && !s then
            onlyMateless += 1
            if onlyMateless <= 5 then out.write(s"Mateless only: $f, ${flagged} flagged\n")
          if s && !m then
            onlyScalachess += 1
            if onlyScalachess <= 5 then out.write(s"scalachess only: $f, ${flagged} flagged\n")
      out.write(s"$n questions ($yes answered yes): Mateless only $onlyMateless, scalachess only $onlyScalachess\n")
    case "material-time" =>
      // ns per call, the same question ("can the side to move never be mated by material?"):
      // Mateless's Material against scalachess's InsufficientMatingMaterial(board, !color); and per
      // ply in games (one per line, FENs joined by |): scalachess's every-move dead check
      // (InsufficientMatingMaterial(board), from autoDraw) against Mateless's flags (both sides,
      // asked only at a halfmove clock of 0, never again once down). 64 items at a time, the two in
      // turn, 9 rounds, median.
      val rounds = 9
      def med(xs: Seq[Double]) = xs.sorted.apply(xs.length / 2)
      def race(name: String, n: Int, a: Int => Int, b: Int => Int, perItem: Int => Int): Unit =
        val ta = Array.fill(rounds)(0.0)
        val tb = Array.fill(rounds)(0.0)
        var sink = 0
        var items = 0L
        for r <- 0 until rounds do
          var c = 0
          var la = 0L
          var lb = 0L
          items = 0
          while c < n do
            val end = math.min(n, c + 64)
            for k <- 0 until 2 do
              val first = ((c >> 6) + r + k) % 2 == 0
              val t0 = System.nanoTime
              var i = c
              while i < end do
                sink += (if first then a(i) else b(i))
                i += 1
              val dt = System.nanoTime - t0
              if first then la += dt else lb += dt
            var i = c
            while i < end do
              items += perItem(i)
              i += 1
            c = end
          ta(r) = la.toDouble / items
          tb(r) = lb.toDouble / items
        out.write(f"$name%-52s Mateless ${med(ta.toSeq)}%6.1f ns   scalachess ${med(tb.toSeq)}%6.1f ns   ($sink)\n")
        out.flush()
      for file <- path.split(',') do
        if file.endsWith(".games") then
          val games = lines(file).filter(_.nonEmpty).map(_.split('|').flatMap(read).toArray).toArray
          def mateless(g: Array[Position]): Int =
            var w = false // White proven unable to mate
            var b = false
            var i = 0
            while i < g.length && !(w && b) do
              val p = g(i)
              if i == 0 || p.history.halfMoveClock.value == 0 then
                if !w && Material.cannotBeMated(p.board, Color.Black) then w = true
                if !b && Material.cannotBeMated(p.board, Color.White) then b = true
              i += 1
            (if w then 1 else 0) + (if b then 2 else 0)
          def scalachess(g: Array[Position]): Int =
            var dead = 0
            var i = 0
            while i < g.length do
              if InsufficientMatingMaterial(g(i).board) then dead += 1
              i += 1
            dead
          race(s"per ply, ${games.length} games (${games.map(_.length).sum} plies)", games.length, i => mateless(games(i)), i => scalachess(games(i)), i => games(i).length)
        else
          val ps = lines(file).filter(_.nonEmpty).flatMap(read).toArray
          race(s"per call, ${ps.length} positions (${file.split('/').last})", ps.length,
            i => if Material.cannotBeMated(ps(i).board, ps(i).color) then 1 else 0,
            i => if InsufficientMatingMaterial(ps(i).board, !ps(i).color) then 1 else 0, _ => 1)
    case "material-loop" =>
      // plain loops over the positions of a FEN list, no closures: Mateless both sides; scalachess's
      // dead check; Mateless both sides with a halfmove-0 condition; 9 rounds, the three in turn
      val ps = lines(path).filter(_.nonEmpty).flatMap(read).toArray
      val n = ps.length
      val t = Array.ofDim[Long](3, 9)
      var sink = 0
      for r <- 0 until 9; k0 <- 0 until 3 do
        val k = (k0 + r) % 3
        val t0 = System.nanoTime
        var i = 0
        k match
          case 0 =>
            while i < n do
              val b = ps(i).board
              if Material.cannotBeMated(b, Color.White) then sink += 1
              if Material.cannotBeMated(b, Color.Black) then sink += 2
              i += 1
          case 1 =>
            while i < n do
              if InsufficientMatingMaterial(ps(i).board) then sink += 1
              i += 1
          case _ =>
            while i < n do
              val p = ps(i)
              if p.history.halfMoveClock.value == 0 then
                if Material.cannotBeMated(p.board, Color.White) then sink += 1
                if Material.cannotBeMated(p.board, Color.Black) then sink += 2
              i += 1
        t(k)(r) = System.nanoTime - t0
      def med(k: Int) = t(k).sorted.apply(4).toDouble / n
      val zero = ps.count(_.history.halfMoveClock.value == 0)
      out.write(f"$n positions (halfmove 0: ${100.0 * zero / n}%.0f%%): Mateless both sides ${med(0)}%.1f ns, scalachess dead check ${med(1)}%.1f ns, Mateless at halfmove 0 ${med(2)}%.1f ns ($sink)\n")
    case other => out.write(s"unknown command $other\n")
  out.flush()
