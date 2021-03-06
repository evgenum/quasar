/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.fs

import slamdata.Predef._
import quasar.{BackendCapability, Data}
import quasar.contrib.pathy._
import quasar.contrib.scalaz.foldable._
import quasar.fp._

import monocle.std.{disjunction => D}
import pathy.Path._
import scalaz._, Scalaz._

class WriteFilesSpec extends FileSystemTest[BackendEffect](
  FileSystemTest.allFsUT.map(_ filter (_.ref supports BackendCapability.write()))) {

  import FileSystemTest._, FileSystemError._
  import WriteFile._

  val query  = QueryFile.Ops[BackendEffect]
  val read   = ReadFile.Ops[BackendEffect]
  val write  = WriteFile.Ops[BackendEffect]
  val manage = ManageFile.Ops[BackendEffect]

  val writesPrefix: ADir = rootDir </> dir("w")

  def deleteForWriting(run: Run): FsTask[Unit] =
    doDelete(run, writesPrefix)

  fileSystemShould { (fs, _) =>
    implicit val run = fs.testInterpM

    "Writing Files" should {
      step(deleteForWriting(fs.setupInterpM).runVoid)

      "write to unknown handle returns UnknownWriteHandle" >>* {
        val h = WriteHandle(rootDir </> file("f1"), 42)
        write.unsafe.write(h, Vector()) map { r =>
          r must_= Vector(unknownWriteHandle(h))
        }
      }

      "write to closed handle returns UnknownWriteHandle" >>* {
        val f = writesPrefix </> dir("d1") </> file("f1")
        val r = for {
          h    <- write.unsafe.open(f)
          _    <- write.unsafe.close(h).liftM[FileSystemErrT]
          errs <- write.unsafe.write(h, Vector()).liftM[FileSystemErrT]
        } yield errs

        r.run.map { xs =>
          D.right
            .composeOptional(vectorFirst[FileSystemError])
            .composePrism(unknownWriteHandle) nonEmpty (xs) must beTrue
        }
      }

      "append should write data to file" >> {
        val f = writesPrefix </> file("saveone")
        val p = write.append(f, oneDoc.toProcess).drain ++ read.scanAll(f)

        runLogT(run, p).runEither must beRight(completelySubsume(oneDoc))
      }

      "append should write many different data objects to file" >> {
        val docs: Vector[Data] =
          Vector(
            Data.Obj(ListMap("a" -> Data.Int(1))),
            Data.Obj(ListMap("a" -> Data.Int(2), "b" -> Data.Str("3"))),
            Data.Obj(ListMap("c" -> Data.Obj(
              ListMap("inner" -> Data.Int(5))
            ))),
            Data.Obj(ListMap("c" -> Data.Arr(List(Data.Int(1), Data.Int(2)))))
          )

        val f = writesPrefix </> file("saveone")
        val p = write.append(f, docs.toProcess).drain ++ read.scanAll(f)

        runLogT(run, p).runEither must beRight(completelySubsume(docs))
      }

      "append two files, one in subdir of the other's parent, should succeed" >> {
        val d = writesPrefix </> dir("subdir1")
        val descendant1 = file[Sandboxed]("subdirfile1")
        val f1 = d </> descendant1
        val descendant2 = dir[Sandboxed]("subdir2") </> file[Sandboxed]("subdirfile2")
        val f2 = d </> descendant2
        val result = write.appendThese(f1, oneDoc) >>
                     write.appendThese(f2, oneDoc) >>
                     query.descendantFiles(d)

        run(result.run).unsafePerformSync must_=== Map[RFile, Node.Type](
          descendant1 -> Node.Data,
          descendant2 -> Node.Data).right
      }

      step(deleteForWriting(fs.setupInterpM).runVoid)
    }
  }
}
