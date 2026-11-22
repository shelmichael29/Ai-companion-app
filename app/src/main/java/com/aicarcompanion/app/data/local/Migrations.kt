package com.aicarcompanion.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: Live Data dashboards. Adds three tables and their indices; no existing data is
 * touched. The SQL is copied from the exported schema (app/schemas/.../2.json).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dashboard_layouts` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`vehicleId`) REFERENCES `vehicles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dashboard_layouts_vehicleId` ON `dashboard_layouts` (`vehicleId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dashboard_gauges` (`layoutId` TEXT NOT NULL, `slot` INTEGER NOT NULL, `pid` INTEGER NOT NULL, " +
                "`style` TEXT NOT NULL, `size` TEXT NOT NULL, `unit` TEXT, PRIMARY KEY(`layoutId`, `slot`), " +
                "FOREIGN KEY(`layoutId`) REFERENCES `dashboard_layouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dashboard_gauges_layoutId_pid` ON `dashboard_gauges` (`layoutId`, `pid`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dashboard_prefs` (`vehicleId` TEXT NOT NULL, `activeLayoutId` TEXT, PRIMARY KEY(`vehicleId`), " +
                "FOREIGN KEY(`vehicleId`) REFERENCES `vehicles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}
