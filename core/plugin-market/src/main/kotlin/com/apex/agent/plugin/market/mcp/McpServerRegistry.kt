package com.apex.agent.plugin.market.mcp

import com.apex.agent.plugin.market.pkg.PluginPackage

/**
 * MCP 远程 server 注册表（Spec §5.1）：已安装插件声明的远程 server
 * 的登记/查询/卸载管理。
 *
 * 与 [LocalMcpRegistry] 的分工：本类管**插件声明的远程端点**（域名
 * 白名单的数据源 —— 安装时权限披露 + 运行时连接层校验），后者管
 * **本地设备函数**（AppFunctions 投影）。同名 server id 的冲突语义：
 * 后装插件覆盖先装（与系统注册表同语义），但**跨插件声明同一 url**
 * 视为共享端点 —— 卸载任一插件不撤端点（引用计数），全部卸载才撤。
 */
class McpServerRegistry {

    private data class Entry(
        val declaration: McpServerDeclaration,
        val ownerPluginIds: MutableSet<String>,
    )

    private val byId = LinkedHashMap<String, Entry>()
    private val byUrl = HashMap<String, MutableSet<String>>()  // url → server ids（引用计数）

    /** 安装插件时登记其全部合法声明（ sanitize 后的合法集）。 */
    fun install(pluginId: String, declarations: List<McpServerDeclaration>) {
        for (d in declarations) {
            val entry = byId.getOrPut(d.id) { Entry(d, mutableSetOf()) }
            if (entry.declaration != d) {
                // 后装覆盖声明（同 id 不同参数 —— 以最新为准）；旧 url 引用
                // 计数递减，集合清空即摘 key（防空集合让 containsKey 误报）
                byUrl[entry.declaration.url]?.remove(d.id)
                if (byUrl[entry.declaration.url]?.isEmpty() == true) {
                    byUrl.remove(entry.declaration.url)
                }
                byId[d.id] = Entry(d, entry.ownerPluginIds)
            }
            byId.getValue(d.id).ownerPluginIds += pluginId
            byUrl.getOrPut(d.url) { mutableSetOf() } += d.id
        }
    }

    /** 卸载插件：引用计数归零的 server 才真正撤除。返回被撤除的 server id。 */
    fun uninstall(pluginId: String): List<String> {
        val removed = mutableListOf<String>()
        val iterator = byId.entries.iterator()
        while (iterator.hasNext()) {
            val (id, entry) = iterator.next()
            entry.ownerPluginIds.remove(pluginId)
            if (entry.ownerPluginIds.isEmpty()) {
                byUrl[entry.declaration.url]?.remove(id)
                if (byUrl[entry.declaration.url]?.isEmpty() == true) byUrl.remove(entry.declaration.url)
                iterator.remove()
                removed += id
            }
        }
        return removed
    }

    fun all(): List<McpServerDeclaration> = byId.values.map { it.declaration }

    fun findById(id: String): McpServerDeclaration? = byId[id]?.declaration

    /** 域名是否已被任一已安装插件声明（运行时连接层白名单查询）。 */
    fun isDeclaredUrl(url: String): Boolean = byUrl.containsKey(url)

    /**
     * 从插件包加载声明（manifest.components.mcpServers 指向的
     * servers.json）：解析 + sanitize，合法集登记，剔除原因透传。
     */
    fun installFromPackage(
        pkg: PluginPackage,
        codec: McpServerDeclarationCodec = McpServerDeclarationCodec,
    ): LoadResult {
        val path = pkg.manifest.components.mcpServers
            ?: return LoadResult(emptyList(), emptyList())
        val text = pkg.entryText(path)
            ?: return LoadResult(emptyList(), listOf("mcpServers 声明文件 \"$path\" 不在包内"))
        val file = codec.parse(text)
            ?: return LoadResult(emptyList(), listOf("servers.json 无法解析"))
        val sanitized = codec.sanitize(file)
        install(pkg.manifest.id, sanitized.servers)
        return LoadResult(sanitized.servers, sanitized.rejections)
    }

    data class LoadResult(
        val installed: List<McpServerDeclaration>,
        val rejections: List<String>,
    )
}
