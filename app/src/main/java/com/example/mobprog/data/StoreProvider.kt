package com.example.mobprog.data

/**
 * Single place that hands out the store. When the SQLite implementation exists,
 * install it in `TudowApp.onCreate` with `StoreProvider.install(SQLiteTodoStore(this))`
 * and nothing else in the UI changes.
 */
object StoreProvider {

    @Volatile
    private var store: TodoStore? = null

    fun get(): TodoStore = store ?: synchronized(this) {
        store ?: InMemoryTodoStore().also { store = it }
    }

    fun install(custom: TodoStore) {
        store = custom
    }
}
