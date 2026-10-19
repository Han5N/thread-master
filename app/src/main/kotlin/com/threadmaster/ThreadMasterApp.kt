package com.threadmaster

import android.app.Application
import com.threadmaster.collector.MonitorEngine
import com.threadmaster.data.AppDatabase
import com.threadmaster.data.SessionRepository
import com.threadmaster.data.SettingsStore

/**
 * 应用级依赖容器。
 *
 * 没有引入 DI 框架：这里的对象图只有四个节点，手工装配比多一个注解处理器更清楚，
 * 也避免在只有 3GB 内存的构建环境里再拉一套 kapt/ksp 依赖。
 */
class ThreadMasterApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var repository: SessionRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var engine: MonitorEngine
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.get(this)
        repository = SessionRepository(database)
        settings = SettingsStore(this)
        engine = MonitorEngine(this, repository)
    }
}
