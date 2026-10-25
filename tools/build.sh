#!/bin/bash
# build.sh <work dir> <a|c|ref> [scalachess commit]
#
# Builds scalachess from source, without sbt and without Maven Central: the Scala compilers come
# from GitHub releases, every dependency from its GitHub tag, at the versions of scalachess's
# build.sbt (18.0.1, a4988192). Then the comparison tools.
#
#   a    scalachess with a-lock/patches (the lock detector; the hook; LockTest)  + check/lock
#   c    scalachess with c-ladder/patches (the whole ladder; the hook; MatelessTest) + check/ladder
#   ref  scalachess as it is, and c-ladder's sources beside it as a library      + check/ladder/Check.scala
#        (for comparing today's scalachess with Mateless: the commands scalachess, bare, material...)
#
#   Scala 3.3.7  for the dependencies (as they are published: built with Scala 3.3 LTS)
#   Scala 3.8.4  for scalalib and scalachess (as scalachess's build)
#
# Result, in <work dir>: jars33/, jars38/ (the dependencies), scalachess-<mode>.jar, and
# classpath-<mode>.txt, for:   java -Xss16m -cp "$(cat <work dir>/classpath-<mode>.txt)" <tool> ...
set -e
W=$(realpath -m "$1"); MODE=$2; COMMIT=${3:-a4988192e6531f92c806e1603a87467c729d5db1}
case "$MODE" in a|c|ref) ;; *) echo "usage: build.sh <work dir> <a|c|ref> [commit]"; exit 1;; esac
HERE=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$W"/src "$W"/jars33 "$W"/jars38 "$W"/classes "$W"/gen
cd "$W"

# ── compilers ────────────────────────────────────────────────────────────────────────────
for v in 3.3.7 3.8.4; do
  if [ ! -d scala3-$v ]; then
    curl -sSfL -o scala3-$v.tar.gz https://github.com/scala/scala3/releases/download/$v/scala3-$v.tar.gz
    tar xzf scala3-$v.tar.gz 2>/dev/null
  fi
done
STD33=$(ls "$W"/scala3-3.3.7/lib/scala-library-2.13*.jar):$(ls "$W"/scala3-3.3.7/lib/scala3-library_3-*.jar)
STD38="$W"/scala3-3.8.4/maven2/org/scala-lang/scala-library/3.8.4/scala-library-3.8.4.jar
SC38="$W"/scala3-3.8.4/bin/scalac

# lib <33|38> <name> "<scalac options>" <source dirs...>: compiles into jars<33|38>/<name>.jar
lib() {
  local V=$1 N=$2 OPTS=$3; shift 3
  [ -f "$W"/jars$V/$N.jar ] && return
  local SC STD DIRS
  if [ $V = 33 ]; then SC="$W"/scala3-3.3.7; STD=$STD33; DIRS="$W/jars33"; else SC="$W"/scala3-3.8.4; STD=$STD38; DIRS="$W/jars33 $W/jars38"; fi
  local CP=$STD:$(for d in $DIRS; do ls $d/*.jar 2>/dev/null; done | tr '\n' ':')
  rm -rf "$W"/classes/$N && mkdir -p "$W"/classes/$N
  find "$@" -name "*.scala" > "$W"/classes/$N.files
  echo "$N (Scala ${V:0:1}.${V:1}): $(wc -l < "$W"/classes/$N.files) files"
  # shellcheck disable=SC2086
  "$SC"/bin/scalac -color:never -nowarn -J-Xss8m -J-Xmx3g $OPTS -classpath "$CP" -d "$W"/classes/$N @"$W"/classes/$N.files
  (cd "$W"/classes/$N && jar cf "$W"/jars$V/$N.jar .)
}
clone() { [ -d src/$1 ] || git clone -q --depth 1 --branch "$3" "https://github.com/$2.git" src/$1; }

# ── dependency sources ───────────────────────────────────────────────────────────────────
clone cats typelevel/cats v2.13.0
clone cats-parse typelevel/cats-parse v1.1.0
clone monocle optics-dev/Monocle v3.3.0
clone shapeless3 typelevel/shapeless-3 v3.5.0
clone kittens typelevel/kittens v3.5.0
clone scalac-compat typelevel/scalac-compat v0.1.4
clone sourcecode com-lihaoyi/sourcecode 0.4.3-M5
clone fansi com-lihaoyi/fansi 0.5.1
clone pprint com-lihaoyi/PPrint 0.9.6
clone scalalib lichess-org/scalalib 11.12.1

# ── cats's generated sources (sbt source generators, run with a stub of sbt's File and IO) ──
if [ ! -d gen/out ]; then
  mkdir -p gen/catsgen
  "$SC38" -color:never -source:3.0-migration -nowarn -d gen/catsgen \
    "$HERE"/tools/catsgen/*.scala src/cats/project/Boilerplate.scala src/cats/project/KernelBoiler.scala src/cats/project/Tuple*Boiler.scala
  java -cp gen/catsgen:"$STD38" catsgen gen/out/kernel gen/out/core > /dev/null
fi

# ── dependencies ─────────────────────────────────────────────────────────────────────────
lib 33 scalac-compat "" src/scalac-compat/annotation/src/main/scala src/scalac-compat/annotation/src/main/scala-3
lib 33 cats-kernel "-Ykind-projector" src/cats/kernel/src/main/scala src/cats/kernel/src/main/scala-2.13+ gen/out/kernel
lib 33 cats-core "-Ykind-projector" src/cats/core/src/main/scala src/cats/core/src/main/scala-2.13+ src/cats/core/src/main/scala-3 gen/out/core
lib 33 alleycats-core "-Ykind-projector" src/cats/alleycats-core/src/main/scala src/cats/alleycats-core/src/main/scala-2.13+
lib 33 cats-free "-Ykind-projector" src/cats/free/src/main/scala src/cats/free/src/main/scala-2.13+ src/cats/free/src/main/scala-3
lib 33 cats-parse "-Ykind-projector" src/cats-parse/core/shared/src/main/scala src/cats-parse/core/jvm/src/main/scala
lib 33 monocle-core "-Ykind-projector -language:implicitConversions,higherKinds,postfixOps" \
  src/monocle/core/shared/src/main/scala src/monocle/core/shared/src/main/scala-3 src/monocle/core/jvm/src/main/scala
lib 33 shapeless3-deriving "" src/shapeless3/modules/deriving/src/main/scala
lib 33 kittens "-Ykind-projector" src/kittens/core/src/main/scala-3
lib 38 sourcecode "" src/sourcecode/sourcecode/src src/sourcecode/sourcecode/src-3
lib 38 fansi "" src/fansi/fansi/src
lib 38 pprint "" src/pprint/pprint/src src/pprint/pprint/src-3
lib 38 scalalib-core "-feature -language:postfixOps -release:21" src/scalalib/core/src/main/scala
lib 38 scalalib-model "-feature -language:postfixOps -release:21" src/scalalib/model/src/main/scala
DEPS=$STD38:$(ls "$W"/jars33/*.jar "$W"/jars38/*.jar | tr '\n' ':')

# ── scalachess, as it is (ref) or with a patch set (a, c), with its own warnings as errors ──
# (plain scalac reports two unused @nowarn in Variant.scala that sbt does not: silenced here)
[ -d src/scalachess ] || git clone -q https://github.com/lichess-org/scalachess.git src/scalachess
T=src/scalachess-$MODE
if [ ! -d $T ]; then
  git -C src/scalachess -c advice.detachedHead=false worktree add -q "$W/$T" "$COMMIT"
  case "$MODE" in
    a) git -C $T -c user.name=build -c user.email=build@localhost am -q "$HERE"/a-lock/patches/*.patch ;;
    c) git -C $T -c user.name=build -c user.email=build@localhost am -q "$HERE"/c-ladder/patches/*.patch ;;
  esac
fi
JAR="$W"/scalachess-$MODE.jar
if [ ! -f "$JAR" ]; then
  rm -rf classes/scalachess-$MODE && mkdir -p classes/scalachess-$MODE
  find $T/core/src/main/scala -name "*.scala" > classes/scalachess-$MODE.files
  echo "scalachess ($MODE, Scala 3.8): $(wc -l < classes/scalachess-$MODE.files) files"
  "$SC38" -color:never -encoding utf-8 -feature -language:postfixOps -release:21 \
    -Wunused:all -Werror -deprecation "-Wconf:msg=@nowarn annotation does not suppress any warnings:s" \
    -J-Xss8m -J-Xmx3g -classpath "$DEPS" -d classes/scalachess-$MODE @classes/scalachess-$MODE.files
  (cd classes/scalachess-$MODE && jar cf "$JAR" .)
fi

# ── the comparison tools ─────────────────────────────────────────────────────────────────
OUT=classes/check-$MODE; rm -rf $OUT && mkdir -p $OUT
case "$MODE" in
  a)   "$SC38" -color:never -release:21 -classpath "$DEPS$JAR" -d $OUT "$HERE"/check/lock/*.scala
       echo "$DEPS$JAR:$W/$OUT" > classpath-a.txt ;;
  c)   "$SC38" -color:never -release:21 -classpath "$DEPS$JAR" -d $OUT "$HERE"/check/ladder/*.scala
       echo "$DEPS$JAR:$W/$OUT" > classpath-c.txt ;;
  ref) mkdir -p $OUT/ladder
       "$SC38" -color:never -encoding utf-8 -release:21 -classpath "$DEPS$JAR" -d $OUT/ladder "$HERE"/c-ladder/*.scala
       "$SC38" -color:never -release:21 -classpath "$DEPS$JAR:$W/$OUT/ladder" -d $OUT "$HERE"/check/ladder/Check.scala
       echo "$DEPS$JAR:$W/$OUT/ladder:$W/$OUT" > classpath-ref.txt ;;
esac
echo "done: java -Xss16m -cp \"\$(cat $W/classpath-$MODE.txt)\" <tool> ..."
