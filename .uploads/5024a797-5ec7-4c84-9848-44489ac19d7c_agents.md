# Minecraft Java Fabric Mod — 项目上下文

## 目标平台

- Minecraft 版本：1.21.11

- Java 版本：21 或更高

- Fabric Loader 版本：0.18.x（以用户实际图片为准，确认后更新此处）

- Fabric API 版本：0.140.2+1.21.11

- Gradle：通过 fabric-loom 插件管理，wrapper 已包含在项目中

- 映射：Mojang 官方映射（官方默认）

## 项目结构

```
modproject/
├── build.gradle                          # Gradle 构建脚本（声明 loom、依赖、构建任务）
├── gradle.properties                     # Mod 元数据 + Gradle 版本号
├── gradle/
│   └── libs.versions.toml                # （可选）版本目录，集中管理依赖版本
├── src/
│   ├── main/java/                        # 服务端 + 共享 Java 源码
│   │   └── <包名>/
│   │       ├── ExampleMod.java           # ModInitializer 入口点（共享逻辑）
│   │       ├── ...                       # 物品 / 方块 / 食谱等注册
│   │       └── event/                    # 事件监听器
│   ├── main/resources/
│   │   ├── fabric.mod.json               # ★ 核心元数据文件（必须存在）
│   │   ├── example-mod.mixins.json       # 共享 Mixin 配置
│   │   ├── example-mod.client.mixins.json# 客户端 Mixin 配置
│   │   └── assets/example-mod/           # 资源文件夹
│   │       ├── icon.png                  # Mod 图标
│   │       ├── textures/                 # 纹理 PNG
│   │       ├── models/                   # 方块/物品模型 JSON
│   │       └── lang/                     # 语言文件（en_us.json 等）
│   ├── client/java/                      # 客户端专属 Java 源码
│   │   └── <包名>/
│   │       └── ExampleModClient.java     # ClientModInitializer 入口点
│   └── client/resources/                 # 客户端专属资源
└── .gitignore
```

## 核心文件说明

### gradle.properties

```properties
# Mod 基本信息（必须修改）
mod_id=examplemod
mod_name=Example Mod
mod_version=1.0.0
maven_group=com.example
archive_base_name=examplemod

# Fabric 版本（按实际更新）
minecraft_version=1.21.11
yarn_mappings=0.140.2+1.21.11
loader_version=0.18.4
fabric_version=0.140.2+1.21.11

# Java 版本
java_version=21
```

### fabric.mod.json — 模组身份证

```json
{
  "schemaVersion": 1,
  "id": "examplemod",
  "version": "${version}",
  "name": "Example Mod",
  "environment": "*",
  "entrypoints": {
    "main": ["com.example.examplemod.ExampleMod"],
    "client": ["com.example.examplemod.client.ExampleModClient"]
  },
  "depends": {
    "fabricloader": ">=0.18.0",
    "minecraft": "~1.21.11",
    "java": ">=21",
    "fabric-api": "*"
  }
}
```

- `id`：模组唯一标识（小写、不含空格）；资源路径全部挂在 `assets/<id>/` 下

- `entrypoints.main`：游戏启动时调用，实现 `ModInitializer` 接口

- `entrypoints.client`：客户端启动时调用，实现 `ClientModInitializer` 接口

- `depends`：声明依赖的 Loader、MC、Java、Fabric API 版本

## 入口点模式

### ModInitializer — 服务端 / 共享初始化

```java
package com.example.examplemod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "examplemod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Example Mod 已加载！");
        // 在此注册物品、方块、事件监听器等
    }
}
```

### ClientModInitializer — 客户端初始化

```java
package com.example.examplemod.client;

import net.fabricmc.api.ClientModInitializer;

public class ExampleModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 注册渲染器、HUD、按键绑定等客户端专属内容
    }
}
```

## 常用注册示例

### 注册自定义方块

```java
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public static final Block EXAMPLE_BLOCK = new Block(
    AbstractBlock.Settings.create().strength(3.0f, 3.0f)
);

public static void registerBlocks() {
    Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "example_block"), EXAMPLE_BLOCK);
    // 注册方块物品
    Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "example_block"),
        new BlockItem(EXAMPLE_BLOCK, new Item.Settings()));
}
```

### 注册事件监听

```java
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

ServerTickEvents.END_SERVER_TICK.register(server -> {
    // 每 tick 执行一次
});
```

## Mixin 用法（最后手段）

优先使用 Fabric API 事件；只有在 Fabric API 无法满足需求时才使用 Mixin。

```java
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "loadWorld", at = @At("HEAD"))
    private void onLoadWorld(CallbackInfo ci) {
        // 在加载世界之前注入自定义逻辑
    }
}
```

Mixin 配置文件示例（`example-mod.mixins.json`）：

```json
{
  "required": true,
  "package": "com.example.examplemod.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["MinecraftServerMixin"]
}
```

并在 `fabric.mod.json` 中声明：

```json
{
  "mixins": ["example-mod.mixins.json"]
}
```

## 资源文件约定

- 纹理路径：`assets/<modid>/textures/block/<name>.png`（16x16 PNG）

- 方块模型：`assets/<modid>/models/block/<name>.json`

- 方块状态：`assets/<modid>/blockstates/<name>.json`

- 物品模型：`assets/<modid>/models/item/<name>.json`

- 语言文件：`assets/<modid>/lang/en_us.json`、`zh_cn.json`

- 合成配方：`data/<modid>/recipes/<name>.json`

所有 `<modid>` 替换为你的 `mod_id`。

## 常用 Gradle 命令

```bash
./gradlew runClient          # 启动开发版客户端（热重载）
./gradlew runServer          # 启动开发版服务端
./gradlew build              # 打包 jar（输出到 build/libs/）
./gradlew genSources         # 生成 Minecraft 反混淆源码，便于 IDE 阅读
```

Windows 下用 `gradlew.bat` 替代 `./gradlew`。

## 术语表

| 术语              | 说明                           |
| --------------- | ---------------------------- |
| Fabric Loader   | 轻量模组加载器；负责加载模组、解析依赖、类转换      |
| Fabric Loom     | Gradle 插件；处理反混淆、映射、IDE 运行配置  |
| Fabric API      | 提供事件钩子、注册表访问、网络通信等 API       |
| Mod ID          | 模组唯一标识，全部小写，资源路径依赖它          |
| Entrypoint      | 游戏启动时调用的初始化入口（main / client） |
| Mixin           | 字节码注入技术，修改原版类（最后手段）          |
| Registry        | 原版的物品/方块/实体注册表               |
| Mappings        | 将混淆名映射为可读名；本项目使用 Mojang 官方映射 |
| Access Widener  | 扩大字段/方法可见性（避免写 Mixin）        |
| Data Generation | 用代码生成 JSON 资源（配方、模型等）        |
| Identifier      | `namespace:path` 格式的资源标识符    |

## 官方文档链接

- Fabric 开发者指南（中文）：<https://docs.fabricmc.net/zh_cn/develop/>

- 创建项目：<https://docs.fabricmc.net/zh_cn/develop/getting-started/creating-a-project>

- 项目结构（1.21.11）：<https://docs.fabricmc.net/1.21.11/develop/getting-started/project-structure>

- Fabric 模板生成器：<https://fabricmc.net/develop/template/>

- Mixin 入门：<https://wiki.fabricmc.net/tutorial:mixin_introduction>

- Fabric Loader 文档：<https://docs.fabricmc.net/zh_cn/1.21.11/develop/loader/>

- Example Mod 参考：<https://github.com/FabricMC/fabric-docs/tree/main/reference/1.21.11>

## 开发注意事项

1. **优先 Fabric API 事件，Mixin 作为最后手段**——Mixin 是版本相关的，多模组改同一方法易冲突。
2. **Mod ID 决定资源路径**——`assets/<modid>/`、`data/<modid>/` 中的 `<modid>` 必须与 `fabric.mod.json` 中的 `id` 一致。
3. **区分客户端和服务端**——渲染、HUD、按键绑定放 `client` 源码集；通用逻辑放 `main`。
4. **不要提交敏感文件**——`.env`、API Key 等不要纳入版本控制。
5. **IDE 路径避免中文和空格**——项目路径如 `C:\Projects\YourMod`，不要放 OneDrive。
6. **Windows 用** **`gradlew.bat`**——不要用 `./gradlew`。
7. **Fabric API mod ID 在 1.19.2 后从** **`fabric`** **改为** **`fabric-api`**——`depends` 中要写 `"fabric-api": "*"`。

