# XH光流 · 网页控制台

无外部运行依赖的局域网网页后台，采用系统字体和原创显示器图标。示例 WLED 地址为 `192.168.1.50`，所有默认布局均为可修改的示例。

## 运行文件

`index.html`、`styles.css`、`model.js`、`app.js`、`console.js` 是五个网页运行文件，图标与 favicon 已内嵌；`mark.svg` 保留可复用的原创矢量源。APK 构建从此目录拷贝这六个文件，不维护第二份网页源码。

没有电视访问凭据时，可直接打开 `index.html` 编辑、导入与导出布局；不会发送 API 请求，也不会操作灯带。连接实际电视时，请使用应用显示的专属配置地址，浏览器请求携带 `X-TVWLED-Token`。API 说明见根目录 README。

## 布局和预览

- C 字形支持一处连续缺口；口字形支持完整四边和断开位置。
- 灯号以屏幕正面为准，可设置首灯、走线方向、WLED 起始编号和各边灯珠数；编号不环绕。
- `layout.geometry.screen/strip` 是独立的场景归一化矩形 `{x,y,width,height}`。拖动和缩放方框不会重排灯号；背面观察只镜像绘图。
- `leds.position` 是灯带局部坐标，`leds.sample` 是屏幕正面归一化取色坐标；导入会根据布局重新计算每灯映射。
- `layout.sampling={inset:0.045,quality:'standard'}`：缩进范围 0–0.2；精度为 `standard`（20 点）或 `fine`（40 点），细腻会增加采样延迟。旧配置缺省为标准。
- 实时预览使用电视实际返回的四边 5/10 点，不根据未保存的草稿伪造点数；屏幕渐变是边缘插值示意，不是画面截图。灯带草稿颜色未模拟颜色校正、亮度与柔化。
- 页面隐藏、停止、断线或数据超过 1.5 秒时清空实时颜色。

模型支持 1–10000 颗灯珠的编辑与导出；当前 Android 输出上限为 480 颗，界面会阻止超限配置保存和启动。

## 设置保存

`output` 包含亮度、柔化、黑边和颜色校正；`preferences.autoStart` 控制开机恢复偏好。`runtime` 缺省为：

```json
{"sleepOff":true,"sourceFilter":{"enabled":false,"allowed":[],"offWhenExcluded":false}}
```

来源选项仅来自电视实际确认列表。条件不满足时保留开启意图；开启接口关灯选项后，切回允许接口可恢复此前开启的同步和灯带电源。

同步关闭时主按钮仅保存；已开启时“保存并应用”先严格回读，再核对本次保存与活动指纹才确认成功。并发保存造成指纹变化时阻止应用并保留草稿。刷新也显示未应用设置。折叠设置不会重建控件或清除草稿，动作文字明确为“开启同步”或“关闭同步”。

## 验证

```sh
node tests.js
node geometry-tests.js
node console-tests.js
node console-api-tests.js
```

测试使用内存 DOM 与合成 API，不连接设备。也可在仓库根运行 `python3 scripts/test.py --web-only`。真实画面、接口和物理灯光效果需要单独验收。
