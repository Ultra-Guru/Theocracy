package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.connector.ConnectorRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * 连接器域 DI（Theocracy 自 SkillModule 迁出 —— skill 域剥离后保留的
 * 独立功能域）。
 *
 * ConnectorRegistry：消息/服务连接器注册表（微信 / 飞书 / Telegram /
 * 自定义 endpoint），消费方：
 * - [AndroidConnectedServicesProvider]（系统提示词 Connected Services 段）
 * - [ToolModule.provideToolRegistry]（ConnectorList / ConnectorSendMessage 工具）
 * - SlashMenuProvider（斜杠菜单连接器分组）
 */
@Module
@InstallIn(SingletonComponent::class)
object ConnectorModule {

    /** 连接器注册表（持久化目录沿用 mcp_config —— 历史数据兼容）。 */
    @Provides
    @Singleton
    fun provideConnectorRegistry(@ApplicationContext context: Context): ConnectorRegistry {
        return ConnectorRegistry(File(context.filesDir, "mcp_config"))
    }
}
