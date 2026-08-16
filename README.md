# Live2D Player

Client-side Live2D model player for Forge 1.20.1. It uses the native
`Live2DCubismCore` library through JNA and exposes Cloth Config for model,
expression, motion, physics, transform, and interaction settings.

## Features

- Loads `.model3.json` packages from `config/live2dplayer/model`.
- Does not bundle or automatically install a default model.
- Reads Cubism 4/5 parameter, drawable, mask, opacity, texture, and canvas data.
- Supports `.motion3.json` actions, `.exp3.json` expressions, `.physics3.json`
  physics, automatic blinking, and movement-driven lip sync.
- Press `F7` to enter edit mode. A frame is drawn around the model; drag the
  frame to move it and drag the bottom-right handle to resize it. Normal mouse
  input is never blocked outside edit mode.
- Live2D parameter, motion, physics, native Core updates, mesh uploads, masking,
  and model drawing run in a shared OpenGL 3.2 worker context. Static UV/index
  buffers stay on the GPU and the Minecraft render thread composites one cached
  quad, synchronized with non-blocking GPU fences.
- Normal mode remains visible in inventory, containers, chat, pause menus, and other screens;
  menu mouse events over the editor are captured so dragging/resizing does not
  also click container slots. The frame and editor input are automatically
  disabled during normal gameplay.
- Requires Cloth Config 11.1.136.

## Controls

- `F6`: Open the Cloth Config screen.
- `F7`: Toggle edit mode, including while a menu is open.
- Left drag inside the edit frame: Move the model.
- Left drag the bottom-right handle: Resize the model.
- Right click inside the edit frame: Cycle expressions.
- Mouse wheel over the model: Scale.
- `F6` menu: toggle multiple expressions and set the render interval under 性能.

## Directories

- `config/live2dplayer/config.json`
- `config/live2dplayer/model`
- `config/live2dplayer/core`
- `config/live2dplayer/cache`

The bundled Windows Core DLL is extracted automatically. Other platforms can set
`-Dlive2dplayer.core=/path/to/Live2DCubismCore.so` or place the native library
under `config/live2dplayer/core`.
