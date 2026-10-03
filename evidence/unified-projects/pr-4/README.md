# PR-4 连接列视觉验收

本目录记录 `feature/connect-columns-web` 的浏览器验收。原型来自只读设计包 `prototype.html`；实现截图使用正式 Vue 组件与静态 API 响应，供布局、交互和权限状态核对，不代表生产部署或真实用户数据。

## 截图对照

| 界面 | 原型（Light） | 实现（Light） |
| --- | --- | --- |
| 来源项目表格 | [原型](prototype-source-light.jpg) | [主表](actual-source-light.jpg)、[展开子项](actual-source-subitems-light.jpg) |
| 目标项目表格 | [原型](prototype-target-light.jpg) | [主表](actual-target-light.jpg) |
| 设置页连接概览 | [原型](prototype-settings-light.jpg) | [完整设置页](actual-settings-light.jpg)、[含入站项目](implementation-settings-light.jpg) |
| 列中心 | [原型](prototype-column-center-light.jpg) | [实现](implementation-column-center-light.jpg) |
| 添加连接列 | [原型](prototype-add-column-light.jpg) | [实现](implementation-add-column-light.jpg) |
| 设置连接列 | [原型](prototype-column-settings-light.jpg) | [实现](implementation-column-settings-light.jpg) |
| 删除连接列 | [原型](prototype-delete-column-light.jpg) | [实现](implementation-delete-column-light.jpg) |
| 成员单元格弹窗 | [原型](prototype-cell-light.jpg) | [实现](implementation-cell-light.jpg) |
| 非目标成员单元格弹窗 | [原型](prototype-cell-nonmember-light.jpg) | [实现](implementation-cell-nonmember-light.jpg) |
| 管理员只读单元格弹窗 | [原型](prototype-cell-admin-light.jpg) | [实现](implementation-cell-admin-light.jpg) |
| 新建并关联 | [原型](prototype-create-light.jpg) | [实现](implementation-create-light.jpg) |
| 连接卡片 | [原型](prototype-card-light.jpg) | [实现](implementation-card-light.jpg) |
| 非目标成员卡片 | [原型](prototype-card-nonmember-light.jpg) | [实现](implementation-card-nonmember-light.jpg) |
| 被连接分组 | [原型](prototype-incoming-light.jpg) | [实现](implementation-incoming-light.jpg) |

Dark 主题：[单元格弹窗](implementation-cell-dark.jpg)、[连接卡片](implementation-card-dark.jpg)。Night 主题：[缺省字段与归档卡片](implementation-card-night.jpg)。原型只提供 Light，深色截图与同一 Light 原型核对结构和信息层级。

## 核对范围

- 原型③、④、⑤均切换双方成员、非目标项目成员和企业管理员只读身份；正式组件的入口依据服务端能力控制。
- 主表连接列位于内置列后，子项复用连接列宽度和单元格组件；被连接列按来源项目与列分组。
- 单目标与多目标、`+N`、空单元格、300ms 搜索、已连接候选、非成员新建、只读卡片及归档状态已核对。
- 连接列设置的行点击与复选框切换、弹窗聚焦、Esc 返回单元格已通过组件回归测试。
- 浏览器验证使用本地静态数据；真实请求格式、批量缓存、过期响应和错误映射由 Vitest 覆盖，后端权限与契约沿用 PR-3。

## 设计差异

删除前的全列连接数和目标项目头像没有相应接口字段，界面保守省略，删除成功后显示实际解除数量。既有主表、子项表各自保存内置列顺序，本次仅共享新增连接列偏好。详细裁决问题见 PR 描述。
