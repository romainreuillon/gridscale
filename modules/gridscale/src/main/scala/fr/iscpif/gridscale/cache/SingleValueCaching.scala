/**
 * Created by Romain Reuillon on 12/06/16.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package fr.iscpif.gridscale.cache

import scala.concurrent.duration._

object SingleValueCaching {

  def apply[T](_expireInterval: T ⇒ Duration)(_compute: () ⇒ T): SingleValueCaching[T] =
    new SingleValueCaching[T] {
      override def compute(): T = _compute()
      override def expiresInterval(t: T): Duration = _expireInterval(t)
    }

  def apply[T](_expireInterval: Duration)(_compute: () ⇒ T): SingleValueCaching[T] =
    apply[T]((t: T) ⇒ _expireInterval)(_compute)

}

trait SingleValueCaching[T] extends (() ⇒ T) {
  @volatile private var cached: Option[(T, Long)] = None

  def compute(): T
  def expiresInterval(t: T): Duration

  override def apply(): T = synchronized {
    cached match {
      case None ⇒
        val value = compute()
        cached = Some((value, System.currentTimeMillis + expiresInterval(value).toMillis))
        value
      case Some((v, expireTime)) if expireTime < System.currentTimeMillis ⇒
        val value = compute()
        cached = Some((value, System.currentTimeMillis + expiresInterval(value).toMillis))
        value
      case Some((v, _)) ⇒ v

    }
  }
}
