# Agent Note: Session 绑定 CSRF 的并发收敛修复

Status: implemented

## Problem

Spring Security 会在安全请求上主动加载 CSRF Token。浏览器缺失或持有失效 CSRF Cookie 时，同一 Session 的并发 GET 曾分别生成随机 Token，并以请求读取到的旧指纹执行比较并交换。只有一个请求能更新成功，其余请求会把正常的并发竞争提升为 `IllegalStateException` 和 HTTP 500；即使允许多次随机写入，响应到达顺序仍可能使浏览器 Cookie 与数据库最终指纹错配。

## Decision

正常登录继续签发随机 Session 与 CSRF 凭据。只有已认证 Session 的 CSRF Cookie 缺失或验证失败、Spring Security 请求生成 Token 时，服务端才使用当前 Session 密钥执行带独立域前缀的 HMAC-SHA256 派生。输入绑定不可变的 Session ID、Session 凭据指纹及其密钥版本，并包含当前密钥版本；输出为 43 字符 Base64URL 凭据。同一 Session 和当前密钥配置在所有实例上得到相同修复 Token，Session 或当前密钥变化后结果自然变化。

持久化使用单条原子更新：数据库中的 CSRF 指纹仍等于请求读取到的旧值，或已经等于相同修复凭据的指纹，均视为成功。并发请求因此幂等收敛并返回相同 Token。Session 不再处于活动状态、指纹已发生其他变化或无法继续认证时返回 `AUTHENTICATION_REQUIRED`，由既有安全过滤器清理 Cookie；该路径不记录 Token、Session 标识或异常敏感信息。

该机制依赖所有应用实例共享相同的当前和上一代 Session 密钥配置，这也是既有 Session 验证与密钥轮换的运行前提。HTTP、OpenAPI、前端并发加载、双提交 Cookie/Header 校验和数据库结构均不改变。

## Alternatives considered

- 前端串行加载：会降低页面加载效率，只约束一个客户端入口，不能修复其他客户端或多标签页触发的服务端竞争。
- 客户端或服务端随机重试：新的随机 Token 仍受数据库提交与 HTTP 响应乱序影响，无法保证浏览器 Cookie 对应最终数据库指纹。
- JVM 内按 Session 加锁：只在单实例内有效，引入锁生命周期和资源回收问题，滚动发布或多实例部署仍会竞争。
- 保存多个同时有效的 CSRF Token：需要新增数据模型、清理策略和验证分支，扩大凭据存储与攻击面，且不符合一次修复收敛到单一凭据的目标。

## Consequences

缺失或失效 CSRF Cookie 的并发安全 GET 可以同时完成，不再因 CAS 竞争随机返回 500；所有修复响应写回同一个 Cookie，后续携带匹配 Cookie/Header 的写请求继续通过。缺失 Header、单边 Token、Token 不匹配、注销、过期和授权失效仍由原安全链拒绝为 401 或 403。

修复 Token 在一个 Session 和当前密钥生命周期内可重复派生，因此必须保持 HMAC 密钥机密、域前缀独立且派生输入仅使用不可变 Session 事实。该 Token 仍只以指纹形式持久化，日志与对象字符串继续脱敏；正常登录生成的随机 CSRF Token 不受影响。
