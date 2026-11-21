
package com.example.mobprog.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TodoDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "tudow.db"
        const val DATABASE_VERSION = 1

        const val TABLE_FOLDERS = "folders"
        const val TABLE_NOTES = "notes"
        const val TABLE_BLOCKS = "blocks"
        const val TABLE_ENTRIES = "entries"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                color TEXT NOT NULL DEFAULT 'default',
                pinned INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                trashed INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (folder_id)
                    REFERENCES folders(id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE blocks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                heading TEXT NOT NULL DEFAULT '',
                origin_note_id INTEGER,
                FOREIGN KEY (note_id)
                    REFERENCES notes(id)
                    ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                block_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                type TEXT NOT NULL DEFAULT 'TEXT',
                text TEXT NOT NULL DEFAULT '',
                checked INTEGER NOT NULL DEFAULT 0,
                priority INTEGER NOT NULL DEFAULT 0,
                deadline_at INTEGER,
                all_day INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (block_id)
                    REFERENCES blocks(id)
                    ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX idx_notes_folder ON notes(folder_id)"
        )

        db.execSQL(
            "CREATE INDEX idx_blocks_note ON blocks(note_id)"
        )

        db.execSQL(
            "CREATE INDEX idx_entries_block ON entries(block_id)"
        )

        db.execSQL(
            """
            INSERT INTO folders (name, created_at)
            VALUES ('Inbox', ${System.currentTimeMillis()})
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Future database migrations will be added here.
        // Existing user data must not be deleted.
    }
}
