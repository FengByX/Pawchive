package com.pawchive.data.di

import com.pawchive.data.repository.DownloadCenter
import com.pawchive.data.repository.DownloadEnqueuer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * data 模块 Hilt 绑定（ARCH-FEATURE-002）。
 *
 * 将 [DownloadEnqueuer] 接口绑定到 [DownloadCenter] 实现，
 * 供规则引擎等依赖接口的组件注入。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindDownloadEnqueuer(impl: DownloadCenter): DownloadEnqueuer
}
