// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

class InMemoryScopeStoreTest : ScopeStoreContractTest() {
    override fun createStore(): ScopeStore = InMemoryScopeStore(limits)
}
