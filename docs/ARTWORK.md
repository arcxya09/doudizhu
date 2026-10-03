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

## v3.0 场景与人物

使用内置图像生成工具制作 `app/src/main/assets/classic_table.webp`（1600×900）和 `app/src/main/assets/characters.webp`（768×512，三个透明角色横向图集）。布局依据用户提供的截图，角色和场景独立制作。

背景提示词：

Create a background-only landscape mobile Chinese Dou Dizhu card game illustration, 16:9 wide. Closely follow this composition: bright blue sky and distant idyllic mountains occupy top third, ornate Chinese courtyard balustrades on the left and right recede toward a central garden, a large golden tan oval card table fills the bottom two thirds, its far curved rim at about 40 percent down the image and its near rim at 94 percent down. Warm gold wood edges, smooth calm light ochre felt playing surface, centered symmetrical perspective. Polished playful 3D casual mobile game aesthetic. Empty playing area, no people, no cards, no buttons, no text, no logos. All decor confined to upper landscape and far left/right margins. The table must be tan and gold, not green.

人物提示词：

A production character sprite atlas on a genuinely transparent background. Exactly three full-body original cheerful Chinese countryside cartoon people in three equal-width vertical columns, each entire body visible from head to shoes, all baseline aligned and same scale, generous transparent space between them, no overlap. Wide 3:2 image. Left column: friendly middle-aged farmer with straw hat, blue cotton vest, cream shirt, brown trousers. Middle column: friendly elderly woman with silver bun, turquoise traditional jacket, navy trousers. Right column: cheerful young adult man with short black hair, orange vest, cream shirt, brown trousers. Polished high quality soft 3D chibi mobile card game style, big expressive heads and short bodies, warm kind smiles. Relaxed standing poses, hands at sides or one hand on hip, looking slightly toward camera. No cards, no props, no text, no logos, no frames, no platform, no scenery, no ground shadows, transparent background.
