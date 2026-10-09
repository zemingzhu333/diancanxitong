# 锅锅堂食 Spring Boot 后端

基于微信云托管 Spring Boot + MySQL 模板扩展。部署目标沿用 CloudBase 环境 `prod-d1dg78eb70011f17b`、服务 `springboot-hmrv`。原有 `/api/count` 示例仍保留。

## 首次初始化

1. 在服务所连接的 MySQL 数据库执行 `src/main/resources/ordering-schema.sql`。
2. 顾客端 AppID 为 `wx2cc993502d30608f`，管理端 AppID 为 `wx6af64e050bd8565d`；两者都须关联到该 CloudBase 环境。
3. 将此代码部署到现有环境和服务后，打开管理端“门店设置”，复制当前管理员 OpenID。
4. 在 CloudRun 服务环境变量中将 `ADMIN_OPENIDS` 设置为管理员 OpenID；多人时用英文逗号分隔。
5. 配置 CloudRun 环境变量 `ADMIN_APP_SECRET`（「小猪厨房」AppSecret）；模板 ID 默认为 `ZJVSCC8CF35EJ9adVkU6W6eMUaz5EHMoVBdF3dGAx4E`，如需覆盖可设置 `ORDER_SUBSCRIBE_TEMPLATE_ID`。不要将 AppSecret 写入小程序或提交到代码仓库。
6. CloudBase 云存储配置菜品图片规则：`dish-covers/` 下文件允许读取；写入和删除仅允许 `ADMIN_OPENIDS` 中的管理员 OpenID。管理端上传时使用 `dish-covers/` 路径，数据库只保存 `fileID`。顾客端通过 `wx.cloud.getTempFileURL` 解析显示图片。
7. 生产环境关闭 CloudRun 公网访问，使用小程序 `wx.cloud.callContainer` 私有链路。

管理员在「小猪厨房」订单页点击“订阅新单通知”并同意后，获得一次订阅额度。一次授权用于一次新订单通知；需要继续收通知时，再次点击并授权。后端仅在订单事务提交后调用微信接口，发送失败不会撤销已经创建的订单。

云存储规则示意（将占位 OpenID 替换为管理员真实 OpenID，并为每位管理员添加一条判断）：

```json
{
  "read": "/^dish-covers\\//.test(resource.path)",
  "write": "auth != null && (auth.openid == '管理员OpenID1' || auth.openid == '管理员OpenID2')"
}
```

详细数据结构和 API 见 `../docs/backend-contract.md`。

## 注意

这个仓库配置了 GitHub 推送自动部署。当前工作在本地分支 `codex/ordering-sync`，尚未提交、推送或部署。完成检查并审核后，再决定是否合并到自动部署分支。
