package com.lspatch.android.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lspatch.android.database.dao.ModuleDao
import com.lspatch.android.database.dao.ScopeDao

import com.lspatch.android.database.entity.Module
import com.lspatch.android.database.entity.Scope

@Database(entities = [Module::class, Scope::class], version = 1)
abstract class LSPDatabase : RoomDatabase() {
    abstract fun moduleDao(): ModuleDao
    abstract fun scopeDao(): ScopeDao
}
