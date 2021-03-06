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

package quasar

import quasar.api._
import quasar.fp._, free._
import quasar.fs.{FileSystemError, PhysicalError}
import quasar.fs.mount.{Mounting, MountingError}
import quasar.fs.mount.module.Module
import quasar.main._

import scalaz.{~>, Monad}

package object server {
  import Mounting.PathTypeMismatch

  def qErrsToResponseT[F[_]: Monad]: QErrs ~> FailedResponseT[F, ?] =
    FailedResponse.fromFailure[F, PhysicalError](_.cause)  :+:
    FailedResponse.fromFailureMessage[F, Module.Error]     :+:
    FailedResponse.fromFailureMessage[F, PathTypeMismatch] :+:
    FailedResponse.fromFailureMessage[F, MountingError]    :+:
    FailedResponse.fromFailureMessage[F, FileSystemError]
}
