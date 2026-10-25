# Mateless for scalachess

[Mateless](https://github.com/cuneytinann/Mateless) proves that a side can never be mated, with
three rungs: a material test, a lock detector (pawns, kings and bishops that no series of legal
moves can open) and a short helpmate search (Domino). Mateless itself is engine-independent
(JavaScript, Rust, WebAssembly).
This repository is its use in [scalachess](https://github.com/lichess-org/scalachess), for Lichess:
two patch sets on scalachess 18.0.1 (`a498819`, 22 Oct 2026).

| | what | lines | timeouts drawn |
|---|---|---|---|
| scalachess 18.0.1 | material, and #821's one-move lookahead | | 136,913 (71.1%) |
| **[a-lock](a-lock)** (proposed) | the lock detector, at the position and in #821's lookahead | 181 + 9 | **187,977 (97.7%)** |
| [c-ladder](c-ladder) (reference) | the whole ladder: material, lock, Domino, a game's flags | 724 + 9 | 192,421 (99.98%) |

The data: chasolver's 192,457 Lichess timeouts up to 31 July 2026 that were scored as wins although
the flagged side could never be mated (Mateless's classification of every row:
[`data/timeouts_classified.tsv`](https://github.com/cuneytinann/Mateless/tree/main/data)). When a flag falls, lila draws the game if
`position.opponentHasInsufficientMaterial` (`Finisher.outOfTime`); both patch sets extend that
function, so lila needs no change. #821 (johndoknjas, merged 23 August 2026) came after the data:
scalachess draws about 70% of the rows of every month from 2017 to 2026.

## a-lock: the lock detector alone

- `core/src/main/scala/mateless/Lock.scala` (181 lines, package `chess.mateless`): the bitboard
  fixed point of Mateless's lock detector, one side at a time, allocating nothing.
- The hook (`Position.opponentHasInsufficientMaterial`, 9 lines): the lock detector at the position
  itself (not in a position without legal moves: the game is over), and in the positions of #821's
  lookahead. Standard and From Position only.
- `LockTest` (37 lines).

Of the 51,064 timeouts it adds, 50,829 are locks at the position itself, and 235 come through the
lookahead: 234 kings stepping out of a pawn's check into a lock, one lock after a forced capture
of a rook. What it leaves: 4,444 positions where a forced line of more than one move is needed
(the ladder's Domino search), and 36 that nothing here proves.

Cost (JVM 21, ns per call, after warm-up): ~150–190 on a locked position (the whole fixed point),
~30–35 on positions of real games (a knight, rook or queen on the board: it stops at once). It
runs only when a flag falls. Mateless's Rust engine: ~105 and ~9 ns.

## c-ladder: the whole ladder

Material, lock and Domino on scalachess's own board, legal moves and positions
(`mateless/Static.scala`, `Domino.scala`, `Ladder.scala`), and the hook asking the ladder
(`opponentCanNeverMate`). Moves are tried in Mateless's order, so proofs, failures and node counts
are those of the JavaScript and Rust engines. A verdict when a flag falls costs ~1–2.5 µs;
`opponentHasInsufficientMaterial` with the hook ~0.9 µs on average (0.6 µs without). A game's two
flags ply by ply (`Flags`, with `rewind` for takebacks) cost ~6.5–7 µs per ply, most of it
scalachess's king-safety test per move (Mateless's own Rust engine: ~0.6 µs).

## Why not the material test

Mateless's material test and scalachess's `InsufficientMatingMaterial` are the same set: 0
differences on every combination of material (each side 0–2 pawns, knights, light and dark
bishops, queens, 0–1 rooks, either side to move: 944,784 questions) and on 1.27 million questions
from chasolver's positions, the timeouts and real games. Per question Mateless's is faster (20–45
against 37–66 ns: scalachess builds small lists on each call), but per ply scalachess's every-move
dead check, one test that stops at its first bitboard, is cheaper (17–20 ns) than asking each side
(37–42 ns, only after a capture or a pawn move). Both are tens of nanoseconds.

## Verification

| | |
|---|---|
| a: lock answers | the same as `mateless.js` on 636,146 positions (chasolver's two files, the timeouts, real games), both sides |
| a: coverage | 187,977 of the 192,457 timeouts with the hook |
| a, c: scalachess's tests | the 22 standard-chess expectations on `opponentHasInsufficientMaterial` (`AutodrawTest`, `VariantTest`) hold with the hook |
| c: against the Rust engine | byte-identical, node counts included: chasolver's 68,950 positions, 119,921 + 192,457 rows, 4,000 + 24,392 + 222,006 games (flags ply by ply), 4,598 takebacks |

scalachess compiles with each patch set under its own flags (`-Wunused:all -Werror`); the files are
formatted with scalafmt 3.11.5 and its `.scalafmt.conf`. munit is not available where this was
built: `LockTest` and `MatelessTest` were not run by munit; their assertions were run by
`check/lock/LockCases.scala` and `check/ladder/MatelessTestCases.scala`. With sbt:
`git am a-lock/patches/*.patch && sbt testKit/test`.

Note for tools reading FENs into scalachess: `Fen.read` drops the halfmove clock;
`Fen.readWithMoveNumber` keeps it (a game's flags read it).

## Layout

| | |
|---|---|
| `a-lock/` | `Lock.scala` and the three commits (`patches/`): the detector, the hook, `LockTest` |
| `c-ladder/` | `Static.scala`, `Domino.scala`, `Ladder.scala` and the three commits: the ladder, the hook, `MatelessTest` |
| `check/lock/` | `LockCheck.scala` (`locks`, `coverage`, `rows`, `time`), `LockCases.scala`, `lock_ref.js` (the same answers from `mateless.js`) |
| `check/ladder/` | `Check.scala`: the Rust tool's `labels`, `layers`, `games` lines, timings, takebacks, and against today's scalachess `scalachess`, `bare`, `material`, `material-time`, `rows`; `TestKitCases.scala`, `MatelessTestCases.scala` |
| `tools/build.sh` | scalachess and its dependencies from source, without sbt or Maven Central: `build.sh <dir> a`, `c` or `ref` |

```sh
tools/build.sh /tmp/w a       # about ten minutes the first time; needs git, curl, Java 21
CP=$(cat /tmp/w/classpath-a.txt)
java -cp "$CP" lockcases
java -Xss16m -cp "$CP" lockcheck coverage timeouts.csv        # chasolver's CSV of the timeouts
java -Xss16m -cp "$CP" lockcheck rows timeouts.csv > a.tsv      # per row: drawn with the patch?
java -Xss16m -cp "$CP" lockcheck locks fens.txt > scala.tsv && MATELESS=../Mateless/mateless.js node check/lock/lock_ref.js fens.txt > js.tsv && cmp js.tsv scala.tsv
```

The commits are authored by Cüneyt İnan. MIT License ([LICENSE](LICENSE)); scalachess itself is
MIT-licensed.
