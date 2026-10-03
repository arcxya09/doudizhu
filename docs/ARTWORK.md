# 原创贴图制作说明

本次使用内置图像生成工具制作背景和人物，随后只进行等比例尺寸归一化和 WebP 编码。运行时素材均从 APK 本地读取。

- `app/src/main/assets/table_background.webp`：1600 × 900 风景桌面，原始生成图为 16:9。
- `app/src/main/assets/avatars.webp`：640 × 320，两名原创对手等宽排列，每格 320 × 320。
- 参考来源：[腾讯《欢乐斗地主》官方页面](https://hlddz.qq.com/)。参考画面层次和场景化牌桌，未复用其角色、品牌或美术文件。
- 扑克牌为公共领域第三方素材，许可独立列于 `CARD_ART_LICENSE.txt`。

## 背景生成提示词

```text
Use case: stylized-concept
Asset type: native Android offline Dou Dizhu game background texture, 16:9 landscape.
Primary request: Original polished cheerful Chinese card-game landscape backdrop. A peaceful warm sunlit countryside with distant blue-green mountains, soft clouds, leafy trees at far edges and a small traditional tiled pavilion at far upper right. Foreground is a broad empty deep emerald green playing-table field with a very subtle oval felt highlight, visually clean and smooth for cards and text. Keep middle 70 percent free of objects, all scenery is concentrated in the top 30 percent and outer edges. Camera straight on like a mobile landscape card-game screen. Warm golden rim light, rich jade greens, soft high quality 3D cartoon illustration, elegant and welcoming to older players, no busy detail.
Constraints: Background only. No cards, no players, no UI, no buttons, no writing, no logos, no watermark. Original art, no proprietary characters. 16:9 wide composition.
```

## 人物生成提示词

```text
Use case: stylized-concept
Asset type: two-avatar texture atlas for a native Android Dou Dizhu game, rectangular image with exactly two equal square portrait panels side-by-side, straight vertical division at 50 percent.
Primary request: Two original friendly Chinese countryside card-game opponent portraits, each centered with chest-up composition and enough margin to crop inside a circle. LEFT: cheerful middle-aged farmer man in a straw hat and blue cotton vest, rounded friendly face. RIGHT: cheerful older woman with silver hair in a neat bun, teal traditional jacket, kind lively smile. Welcoming polished 3D cartoon mobile game portrait style, soft warm studio lighting, clear facial features. Each portrait on a matching quiet muted dark jade green background, consistent scale and style.
Constraints: Exactly two equal square panels side by side in a 2:1 atlas. No text, no UI, no cards, no logos, no watermarks. Original characters, no proprietary characters.
```
