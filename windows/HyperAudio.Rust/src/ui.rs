use crate::backend::{Backend, Snapshot, addresses};
use gpui::prelude::FluentBuilder;
use gpui::*;
use image::RgbaImage;
use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use tray_icon::{
    MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent,
    menu::{Menu, MenuEvent, MenuItem},
};
use windows::Win32::{
    Foundation::HWND,
    UI::WindowsAndMessaging::{SW_HIDE, SW_SHOW, SetForegroundWindow, ShowWindow},
};

const INK: u32 = 0x1c1c27;
const MUTED: u32 = 0x81818d;
const ACCENT: u32 = 0x4545b8;

pub struct MainView {
    backend: Arc<Backend>,
    logo: Arc<RenderImage>,
    qr: Option<Arc<RenderImage>>,
    uri: Option<String>,
    expires: Option<Instant>,
    page: usize,
    error: Option<String>,
    delete: Option<String>,
    tray: Option<TrayIcon>,
    preferences: crate::settings::Preferences,
    startup_enabled: bool,
    menu_ids: Option<(String, String, String)>,
}
fn render_image(mut rgba: RgbaImage) -> Arc<RenderImage> {
    for pixel in rgba.pixels_mut() {
        pixel.0.swap(0, 2);
    }
    Arc::new(RenderImage::new(vec![image::Frame::new(rgba)]))
}
fn native_visibility(window: &Window, show: bool) {
    if let Ok(handle) = HasWindowHandle::window_handle(window) {
        if let RawWindowHandle::Win32(raw) = handle.as_raw() {
            unsafe {
                let hwnd = HWND(raw.hwnd.get() as _);
                let _ = ShowWindow(hwnd, if show { SW_SHOW } else { SW_HIDE });
                if show {
                    let _ = SetForegroundWindow(hwnd);
                }
            }
        }
    }
}
fn panel() -> Div {
    div()
        .flex()
        .flex_col()
        .gap_4()
        .p_6()
        .bg(rgb(0xffffff))
        .rounded_2xl()
        .border_1()
        .border_color(rgb(0xeeeef2))
}
fn caption(text: impl Into<SharedString>) -> Div {
    div()
        .text_size(px(13.))
        .text_color(rgb(MUTED))
        .child(text.into())
}
fn button(id: impl Into<ElementId>, text: impl Into<SharedString>, primary: bool) -> Stateful<Div> {
    div()
        .id(id)
        .px_4()
        .py_3()
        .rounded_lg()
        .cursor_pointer()
        .text_size(px(14.))
        .font_weight(FontWeight::MEDIUM)
        .bg(rgb(if primary { ACCENT } else { 0xf0f0f6 }))
        .text_color(rgb(if primary { 0xffffff } else { ACCENT }))
        .hover(|style| style.bg(rgb(if primary { 0x383896 } else { 0xe5e5ef })))
        .child(text.into())
}
fn toggle(id: impl Into<ElementId>, enabled: bool) -> Stateful<Div> {
    div()
        .id(id)
        .w(px(46.))
        .h(px(26.))
        .px(px(3.))
        .flex()
        .items_center()
        .map(|style| {
            if enabled {
                style.justify_end()
            } else {
                style.justify_start()
            }
        })
        .rounded_full()
        .cursor_pointer()
        .bg(rgb(if enabled { ACCENT } else { 0xc8c8d0 }))
        .child(div().size(px(20.)).rounded_full().bg(rgb(0xffffff)))
}
impl MainView {
    pub fn new(backend: Arc<Backend>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let logo_rgba = image::load_from_memory(include_bytes!("../assets/app.png"))
            .unwrap()
            .to_rgba8();
        let logo = render_image(logo_rgba.clone());
        let tray_setup = (|| -> anyhow::Result<_> {
            let menu = Menu::new();
            let open = MenuItem::new("打开 HyperAudio", true, None);
            let stop = MenuItem::new("停止投送", true, None);
            let exit = MenuItem::new("退出", true, None);
            menu.append_items(&[&open, &stop, &exit])?;
            let icon = tray_icon::Icon::from_rgba(
                image::imageops::resize(&logo_rgba, 32, 32, image::imageops::FilterType::Lanczos3)
                    .into_raw(),
                32,
                32,
            )?;
            let tray = TrayIconBuilder::new()
                .with_tooltip("HyperAudio Bridge")
                .with_icon(icon)
                .with_menu(Box::new(menu))
                .build()?;
            Ok((
                tray,
                (
                    open.id().0.clone(),
                    stop.id().0.clone(),
                    exit.id().0.clone(),
                ),
            ))
        })();
        let (tray, menu_ids, mut error) = match tray_setup {
            Ok((tray, ids)) => (Some(tray), Some(ids), None),
            Err(e) => (None, None, Some(format!("托盘不可用：{e}"))),
        };
        let mut preferences = crate::settings::Preferences::load().unwrap_or_else(|e| {
            error = Some(format!("无法读取设置：{e}"));
            Default::default()
        });
        if !preferences.show_tray {
            if let Some(tray) = &tray {
                if let Err(e) = tray.set_visible(false) {
                    preferences.show_tray = true;
                    error = Some(format!("无法隐藏托盘：{e}"));
                }
            }
        }
        let hide = tray.is_some();
        window.on_window_should_close(cx, move |window, cx| {
            if hide {
                native_visibility(window, false);
                false
            } else {
                cx.quit();
                true
            }
        });
        cx.spawn_in(window, async move |view, cx| {
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(500))
                    .await;
                if view
                    .update_in(cx, |this, window, cx| {
                        if this.expires.is_some_and(|until| Instant::now() >= until) {
                            this.qr = None;
                            this.uri = None;
                            this.expires = None;
                        }
                        if let Some((open, stop, exit)) = &this.menu_ids {
                            while let Ok(event) = MenuEvent::receiver().try_recv() {
                                if event.id.0 == *open {
                                    native_visibility(window, true);
                                    window.activate_window();
                                } else if event.id.0 == *stop {
                                    this.backend.stop();
                                } else if event.id.0 == *exit {
                                    this.backend.stop();
                                    cx.quit();
                                }
                            }
                        }
                        while let Ok(event) = TrayIconEvent::receiver().try_recv() {
                            if matches!(
                                event,
                                TrayIconEvent::Click {
                                    button: MouseButton::Left,
                                    button_state: MouseButtonState::Up,
                                    ..
                                } | TrayIconEvent::DoubleClick {
                                    button: MouseButton::Left,
                                    ..
                                }
                            ) {
                                native_visibility(window, true);
                                window.activate_window();
                            }
                        }
                        cx.notify();
                    })
                    .is_err()
                {
                    break;
                }
            }
        })
        .detach();
        Self {
            backend,
            logo,
            qr: None,
            uri: None,
            expires: None,
            page: 0,
            error,
            delete: None,
            tray,
            preferences,
            startup_enabled: crate::settings::startup_enabled(),
            menu_ids,
        }
    }
    fn generate(&mut self) {
        match self.backend.pairing_uri().and_then(|uri| {
            let code = qrcode::QrCode::new(uri.as_bytes())?;
            let luma = code
                .render::<image::Luma<u8>>()
                .min_dimensions(280, 280)
                .quiet_zone(true)
                .build();
            Ok((
                uri,
                render_image(image::DynamicImage::ImageLuma8(luma).to_rgba8()),
            ))
        }) {
            Ok((uri, qr)) => {
                self.uri = Some(uri);
                self.qr = Some(qr);
                self.expires = Some(Instant::now() + Duration::from_secs(300));
                self.error = None;
            }
            Err(e) => self.error = Some(e.to_string()),
        }
    }
    fn devices(&self, state: &Snapshot, cx: &mut Context<Self>) -> Div {
        let mut list = panel().flex_1().min_w(px(260.)).child(
            div()
                .flex()
                .justify_between()
                .child(div().font_weight(FontWeight::SEMIBOLD).child("已配对设备"))
                .child(caption(format!("{} 台", state.phones.len()))),
        );
        if state.phones.is_empty() {
            list = list.child(
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .justify_center()
                    .gap_3()
                    .h(px(180.))
                    .child(img(self.logo.clone()).size(px(72.)).rounded_xl())
                    .child(caption("暂无设备")),
            );
        }
        for (id, name) in &state.phones {
            let pending = self.delete.as_ref() == Some(id);
            let id = id.clone();
            let remove_id = id.clone();
            let cancel_id = id.clone();
            let mut row =
                div()
                    .flex()
                    .flex_col()
                    .gap_3()
                    .py_4()
                    .border_b_1()
                    .border_color(rgb(0xf0f0f4))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .justify_between()
                            .child(div().flex().flex_col().gap_1().child(name.clone()).child(
                                caption(if state.active.as_ref() == Some(name) {
                                    "正在投送"
                                } else {
                                    "已配对"
                                }),
                            ))
                            .child(
                                button(
                                    SharedString::from(format!("remove-{id}")),
                                    if pending { "取消" } else { "删除" },
                                    false,
                                )
                                .on_click(cx.listener(
                                    move |this, _, _, cx| {
                                        this.delete = if this.delete.as_ref() == Some(&id) {
                                            None
                                        } else {
                                            Some(id.clone())
                                        };
                                        cx.notify();
                                    },
                                )),
                            ),
                    );
            if pending {
                row = row.child(caption("删除后需重新配对")).child(
                    button(
                        SharedString::from(format!("confirm-{cancel_id}")),
                        "确认删除",
                        false,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        if let Err(e) = this.backend.forget(&remove_id) {
                            this.error = Some(e.to_string());
                        }
                        this.delete = None;
                        cx.notify();
                    })),
                );
            }
            list = list.child(row);
        }
        list
    }
}
impl Render for MainView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let state = self.backend.snapshot();
        let mut sidebar = div()
            .flex()
            .flex_col()
            .w(px(176.))
            .flex_shrink_0()
            .p_5()
            .gap_2()
            .bg(rgb(0xf5f1e8))
            .border_r_1()
            .border_color(rgb(0xece8e0))
            .child(img(self.logo.clone()).size(px(64.)).rounded_xl().mb_1())
            .child(
                div()
                    .text_size(px(19.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .mb_6()
                    .child("HyperAudio"),
            );
        for (index, label) in ["接收", "设备", "设置"].into_iter().enumerate() {
            sidebar = sidebar.child(
                div()
                    .id(index)
                    .rounded_lg()
                    .px_4()
                    .py_3()
                    .cursor_pointer()
                    .text_size(px(14.))
                    .bg(rgb(if self.page == index {
                        0xe7e3f1
                    } else {
                        0xf5f1e8
                    }))
                    .text_color(rgb(if self.page == index { ACCENT } else { 0x727078 }))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.page = index;
                        cx.notify();
                    }))
                    .child(label),
            );
        }
        sidebar = sidebar
            .child(div().flex_1())
            .child(caption(env!("CARGO_PKG_VERSION")));
        let title = ["音频接收", "设备管理", "设置"][self.page];
        let mut main = div()
            .id("main-scroll")
            .flex()
            .flex_col()
            .flex_1()
            .gap_6()
            .p_8()
            .overflow_y_scroll()
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap_1()
                    .child(
                        div()
                            .text_size(px(30.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .child(title),
                    )
                    .child(caption(
                        std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows".into()),
                    )),
            );
        if let Some(error) = &self.error {
            main = main.child(
                div()
                    .p_4()
                    .rounded_xl()
                    .bg(rgb(0xffeee8))
                    .text_color(rgb(0xa24634))
                    .text_size(px(13.))
                    .child(error.clone()),
            );
        }
        if self.page == 0 {
            main = main.child(
                div()
                    .flex()
                    .gap_4()
                    .child(
                        panel()
                            .flex_1()
                            .bg(rgb(0xeeedf8))
                            .border_color(rgb(0xe6e3f5))
                            .child(caption("接收状态"))
                            .child(
                                div()
                                    .text_size(px(25.))
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .text_color(rgb(ACCENT))
                                    .child(state.status.clone()),
                            ),
                    )
                    .child(
                        panel().w(px(180.)).child(caption("已配对设备")).child(
                            div()
                                .text_size(px(28.))
                                .font_weight(FontWeight::SEMIBOLD)
                                .child(state.phones.len().to_string()),
                        ),
                    ),
            );
            let mut pairing = panel().w(px(324.)).flex_shrink_0().items_center().child(
                div()
                    .w_full()
                    .font_weight(FontWeight::SEMIBOLD)
                    .child("添加手机"),
            );
            if let Some(qr) = &self.qr {
                let seconds = self
                    .expires
                    .map(|v| v.saturating_duration_since(Instant::now()).as_secs())
                    .unwrap_or(0);
                pairing = pairing
                    .child(img(qr.clone()).size(px(260.)))
                    .child(caption(format!(
                        "手机扫码 · {}:{:02} 后失效",
                        seconds / 60,
                        seconds % 60
                    )))
                    .child(
                        button("copy-pairing", "复制配对地址", false).on_click(cx.listener(
                            |this, _, _, cx| {
                                if let Some(uri) = &this.uri {
                                    cx.write_to_clipboard(ClipboardItem::new_string(uri.clone()));
                                }
                            },
                        )),
                    )
                    .child(
                        button("new-pairing", "重新生成", false).on_click(cx.listener(
                            |this, _, _, cx| {
                                this.generate();
                                cx.notify();
                            },
                        )),
                    );
            } else {
                pairing = pairing
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .items_center()
                            .justify_center()
                            .gap_4()
                            .h(px(200.))
                            .child(img(self.logo.clone()).size(px(94.)).rounded_2xl())
                            .child(caption("生成配对码后，在手机端扫码")),
                    )
                    .child(button("generate", "生成配对码", true).on_click(cx.listener(
                        |this, _, _, cx| {
                            this.generate();
                            cx.notify();
                        },
                    )));
            }
            main = main.child(
                div()
                    .flex()
                    .gap_5()
                    .items_start()
                    .child(self.devices(&state, cx))
                    .child(pairing),
            );
            if let Some(name) = &state.active {
                main =
                    main.child(
                        panel().child(
                            div()
                                .flex()
                                .items_center()
                                .justify_between()
                                .child(
                                    div()
                                        .flex()
                                        .flex_col()
                                        .gap_1()
                                        .child(name.clone())
                                        .child(caption("正在接收手机音频")),
                                )
                                .child(button("stop-stream", "停止投送", true).on_click(
                                    cx.listener(|this, _, _, cx| {
                                        this.backend.stop();
                                        cx.notify();
                                    }),
                                )),
                        ),
                    );
            }
            main = main.child(caption(if addresses().is_empty() {
                "未连接局域网"
            } else {
                "网络已连接 · 自动发现"
            }));
        } else if self.page == 1 {
            main = main
                .child(self.devices(&state, cx))
                .child(caption("添加设备请在接收页生成配对码"));
        } else {
            main = main
                .child(
                    panel()
                        .child(div().font_weight(FontWeight::SEMIBOLD).child("常规"))
                        .child(
                            div()
                                .flex()
                                .items_center()
                                .justify_between()
                                .child(
                                    div()
                                        .child("开机自启动")
                                        .child(caption("登录 Windows 后在后台运行")),
                                )
                                .child(toggle("startup", self.startup_enabled).on_click(
                                    cx.listener(|this, _, _, cx| {
                                        match crate::settings::set_startup(!this.startup_enabled) {
                                            Ok(()) => this.startup_enabled = !this.startup_enabled,
                                            Err(e) => {
                                                this.error = Some(format!("无法修改自启动：{e}"))
                                            }
                                        }
                                        cx.notify();
                                    }),
                                )),
                        )
                        .child(
                            div()
                                .flex()
                                .items_center()
                                .justify_between()
                                .child(
                                    div()
                                        .child("显示托盘图标")
                                        .child(caption("隐藏后可通过应用快捷方式打开窗口")),
                                )
                                .child(toggle("show-tray", self.preferences.show_tray).on_click(
                                    cx.listener(|this, _, _, cx| {
                                        let visible = !this.preferences.show_tray;
                                        let result = (|| -> anyhow::Result<()> {
                                            let tray = this
                                                .tray
                                                .as_ref()
                                                .ok_or_else(|| anyhow::anyhow!("托盘不可用"))?;
                                            tray.set_visible(visible)?;
                                            let previous = this.preferences.show_tray;
                                            this.preferences.show_tray = visible;
                                            if let Err(e) = this.preferences.save() {
                                                this.preferences.show_tray = previous;
                                                let _ = tray.set_visible(previous);
                                                return Err(e);
                                            }
                                            Ok(())
                                        })();
                                        if let Err(e) = result {
                                            this.error = Some(format!("无法修改托盘设置：{e}"));
                                        }
                                        cx.notify();
                                    }),
                                )),
                        ),
                )
                .child(
                    panel()
                        .child(div().font_weight(FontWeight::SEMIBOLD).child("音频输出"))
                        .child("系统默认设备")
                        .child(caption("切换输出设备后自动恢复")),
                )
                .child(
                    panel()
                        .child(div().font_weight(FontWeight::SEMIBOLD).child("诊断日志"))
                        .child(caption("由手机端开关控制 · 不记录音频与凭证"))
                        .child(
                            button("open-logs", "打开日志目录", false).on_click(cx.listener(
                                |this, _, _, cx| {
                                    let path = crate::identity::directory();
                                    let result = std::fs::create_dir_all(&path).and_then(|_| {
                                        std::process::Command::new("explorer.exe")
                                            .arg(path)
                                            .spawn()
                                            .map(|_| ())
                                    });
                                    if let Err(e) = result {
                                        this.error = Some(e.to_string());
                                        cx.notify();
                                    }
                                },
                            )),
                        ),
                )
                .child(
                    panel()
                        .child(caption(format!("接收帧数  {}", state.frames)))
                        .child(caption(format!("解码采样  {}", state.decoded)))
                        .child(caption(format!("播放采样  {}", state.rendered))),
                )
                .child(button("quit-app", "退出应用", false).on_click(cx.listener(
                    |this, _, _, cx| {
                        this.backend.stop();
                        cx.quit();
                    },
                )))
                .child(caption(concat!(
                    "HyperAudio Bridge ",
                    env!("CARGO_PKG_VERSION")
                )));
        }
        div()
            .flex()
            .size_full()
            .bg(rgb(0xf8f8fa))
            .font_family("Microsoft YaHei UI")
            .text_color(rgb(INK))
            .text_size(px(15.))
            .child(sidebar)
            .child(main)
    }
}

pub fn run(backend: Arc<Backend>, background: bool) {
    Application::new().run(move |cx: &mut App| {
        let bounds = Bounds::centered(None, size(px(1080.), px(780.)), cx);
        cx.open_window(
            WindowOptions {
                show: !background,
                focus: !background,
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                window_min_size: Some(size(px(940.), px(680.))),
                titlebar: Some(TitlebarOptions {
                    title: Some("HyperAudio Bridge".into()),
                    ..Default::default()
                }),
                ..Default::default()
            },
            move |window, cx| cx.new(|cx| MainView::new(backend.clone(), window, cx)),
        )
        .unwrap();
        if !background {
            cx.activate(true);
        }
    });
}
