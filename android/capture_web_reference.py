#!/usr/bin/env python3
"""Capture the canonical mobile frontend against visual_reference.py, never a deployment.

Optional --android-fonts points to Roboto TTFs pulled from the test emulator.
Playwright is a development tool only; it is not included in the APK.
"""

import argparse
import base64
import json
from pathlib import Path

from playwright.sync_api import sync_playwright


def check_sidebar(browser, output):
    measurements = []
    for theme in ("classic", "glass", "vitrine"):
        for width in (900, 1280):
            page = browser.new_page(viewport={"width": width, "height": 850}, color_scheme="dark")
            page.route(
                "**/*",
                lambda route: (
                    route.continue_() if route.request.url.startswith("http://127.0.0.1:8765/") else route.abort()
                ),
            )
            page.goto(f"http://127.0.0.1:8765/?theme={theme}")
            page.wait_for_selector(".sidebar-item .sidebar-cover")
            page.add_style_tag(content="*,*::before,*::after{transition:none!important;animation:none!important;}")
            page.evaluate("""() => {
                const list=document.querySelector('.sidebar-list'), sample=list.querySelector('.sidebar-item');
                for(let i=0;i<30;i++)list.append(sample.cloneNode(true));
            }""")
            for mode in ("expanded", "collapsed", "expanded", "collapsed"):
                page.evaluate("mode=>document.documentElement.dataset.sidebarMode=mode", mode)
                page.wait_for_function(
                    """() => Math.abs(document.querySelector('.sidebar').getBoundingClientRect().width - parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sidebar-width'))) < 1"""
                )
                data = page.evaluate("""() => {
                    const sidebar=document.querySelector('.sidebar'), list=document.querySelector('.sidebar-list'),s=sidebar.getBoundingClientRect();
                    const offsets=Array.from(list.querySelectorAll('.sidebar-item .sidebar-cover')).map(c=>{const r=c.getBoundingClientRect();return r.x+r.width/2-s.x-s.width/2;});
                    list.scrollTop=list.scrollHeight;
                    return {offsets,scroll:list.scrollTop,scrollbar:getComputedStyle(list).scrollbarWidth};
                }""")
                assert data["scroll"] > 0, f"{theme}/{width}: library must remain scrollable"
                assert data["scrollbar"] == "none", f"{theme}/{width}: hidden scrollbar regressed"
                if mode == "collapsed":
                    assert all(abs(value) <= 0.51 for value in data["offsets"]), (
                        f"{theme}/{width}: off-centre covers {data['offsets']}"
                    )
                measurements.append({"theme": theme, "width": width, "mode": mode, **data})
            page.locator(".sidebar").screenshot(path=str(output / f"sidebar-{theme}-{width}.png"))
            page.close()
    (output / "sidebar-layout.json").write_text(json.dumps(measurements, indent=2) + "\n")


def check_collection_titles(browser, output):
    measurements = []
    for theme in ("classic", "glass", "vitrine"):
        for scheme in ("dark", "light"):
            for width in (320, 400, 1280):
                page = browser.new_page(viewport={"width": width, "height": 850}, color_scheme=scheme)
                page.route("**/*", lambda route: route.continue_() if route.request.url.startswith("http://127.0.0.1:8765/") else route.abort())
                page.goto(f"http://127.0.0.1:8765/?theme={theme}")
                page.wait_for_function("typeof window.loadView === 'function'")
                page.evaluate("loadView('playlist', '1')")
                page.wait_for_selector(".playlist-title")
                page.evaluate("document.fonts.ready")
                for title in ("Trap", "Liked Songs", "2000s", "Evening essentials", "Álbum del verano", "A" * 180):
                    data = page.evaluate("""value => {
                        const h=document.querySelector('.playlist-title');h.textContent=value;
                        const probe=h.cloneNode(true);probe.removeAttribute('id');
                        Object.assign(probe.style,{position:'fixed',visibility:'hidden',whiteSpace:'nowrap',width:'max-content',maxWidth:'none',margin:'0'});
                        h.parentElement.append(probe);const needed=probe.getBoundingClientRect().width;probe.remove();
                        const range=document.createRange();range.selectNodeContents(h);
                        const rects=[...range.getClientRects()];
                        return {lines:new Set(rects.map(r=>r.top)).size,
                            right:Math.max(...rects.map(r=>r.right)),
                            width:innerWidth,pageWidth:document.documentElement.scrollWidth,
                            needed,available:h.parentElement.clientWidth};
                    }""", title)
                    if len(title) < 40 and data["needed"] <= data["available"]:
                        assert data["lines"] == 1, f"{theme}/{scheme}/{width}/{title}: {data}"
                    assert data["right"] <= data["width"] + 1, (theme, width, title, data)
                    assert data["pageWidth"] <= data["width"] + 1, (theme, width, title, data)
                    measurements.append({"theme": theme, "scheme": scheme, "viewport": width, "title": title, **data})
                page.close()
    (output / "collection-title-layout.json").write_text(json.dumps(measurements, indent=2) + "\n")


def capture():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--android-fonts", type=Path)
    parser.add_argument("--safe-top", type=int, default=24)
    parser.add_argument("--safe-bottom", type=int, default=48)
    parser.add_argument("--write-theme-tokens", action="store_true")
    parser.add_argument(
        "--tokens-only", action="store_true", help="Read the final CSS cascade without capturing screenshots"
    )
    parser.add_argument("--sidebar-only", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    font_css = ""
    if args.android_fonts:
        for weight, name in [(400, "Regular"), (500, "Medium"), (600, "Medium"), (700, "Bold")]:
            data = base64.b64encode((args.android_fonts / f"Roboto-{name}.ttf").read_bytes()).decode()
            font_css += (
                f"@font-face{{font-family:AndroidReference;src:url(data:font/ttf;base64,{data});font-weight:{weight};}}"
            )
        font_css += "*{font-family:AndroidReference,sans-serif!important;}"
    tokens = {}
    failures = []
    measurements = {}
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        if not args.tokens_only:
            check_sidebar(browser, args.output)
        if args.sidebar_only:
            browser.close()
            print("Verified centred covers and hidden, working scrolling across three desktop themes and two widths.")
            return
        if not args.tokens_only:
            check_collection_titles(browser, args.output)
        for theme in ("classic", "glass", "vitrine"):
            tokens[theme] = {}
            for scheme in ("dark", "light"):
                page = browser.new_page(
                    viewport={"width": 400, "height": 850},
                    is_mobile=True,
                    has_touch=True,
                    color_scheme=scheme,
                    reduced_motion="reduce",
                )
                page.route(
                    "**/*",
                    lambda route: (
                        route.continue_() if route.request.url.startswith("http://127.0.0.1:8765/") else route.abort()
                    ),
                )
                page.on("pageerror", lambda error: failures.append(str(error)))
                page.goto(f"http://127.0.0.1:8765/?theme={theme}")
                page.wait_for_function("typeof window.loadView === 'function'")
                page.wait_for_selector(".home-rail .card, .party-card")
                page.wait_for_timeout(400)
                tokens[theme][scheme] = page.evaluate(r"""() => {
                  const root=getComputedStyle(document.documentElement), canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');
                  const colors={};for(const name of ['background','surface-0','surface-1','surface-2','surface-3','text-main','text-sub','text-faint','primary','border-subtle','border-strong','square-content','square-rim','square-wash','square-film','square-film-strong','square-glint','square-line']){
                    ctx.clearRect(0,0,1,1);ctx.fillStyle=root.getPropertyValue('--'+name).trim();ctx.fillRect(0,0,1,1);
                    const [r,g,b,a]=ctx.getImageData(0,0,1,1).data;colors[name]='#'+[a,r,g,b].map(x=>x.toString(16).padStart(2,'0')).join('');
                  }
                  const nav=Array.from(document.querySelectorAll('.m-nav-item')).sort((a,b)=>Number(getComputedStyle(a).order)-Number(getComputedStyle(b).order)).map(e=>e.dataset.view);
                  const overlay=document.querySelector('#downloads-modal'),modal=overlay.querySelector('.modal'),head=modal.querySelector('.modal-header'),ms=getComputedStyle(modal),hs=getComputedStyle(head);
                  function color(value){ctx.clearRect(0,0,1,1);ctx.fillStyle=value;ctx.fillRect(0,0,1,1);const p=ctx.getImageData(0,0,1,1).data;return '#'+[p[3],p[0],p[1],p[2]].map(x=>x.toString(16).padStart(2,'0')).join('');}
                  colors['modal-background']=color(ms.backgroundColor);colors['modal-border']=color(ms.borderTopColor);colors['modal-scrim']=color(getComputedStyle(overlay).backgroundColor);colors['modal-header-line']=color(hs.borderBottomColor);
                  const modalStyle={padding:parseFloat(ms.paddingLeft),margin:parseFloat(ms.marginLeft),border:parseFloat(ms.borderLeftWidth),radii:['borderTopLeftRadius','borderTopRightRadius','borderBottomRightRadius','borderBottomLeftRadius'].map(k=>parseFloat(ms[k])),headerPadding:parseFloat(hs.paddingBottom),headerGap:parseFloat(hs.marginBottom),titleSize:parseFloat(getComputedStyle(head.querySelector('h2')).fontSize)};
                  const title=head.querySelector('h2'),savedClass=title.className;title.className='';modalStyle.generalTitleSize=parseFloat(getComputedStyle(title).fontSize);title.className=savedClass;
                  const blur=root.getPropertyValue('--square-blur'),material={blur:Number(blur.match(/blur\(([\d.]+)px\)/)?.[1]||0),saturation:Number(blur.match(/saturate\(([\d.]+)\)/)?.[1]||1)};
                  return {colors,nav,modal:modalStyle,material,header:getComputedStyle(document.querySelector('.m-topbar')).padding,radius:root.getPropertyValue('--radius-pill').trim()||'9999px'};
                }""")
                tokens[theme][scheme]["components"] = page.evaluate(r"""async () => {
                  await loadView('settings_user');
                  const canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');
                  function color(value){ctx.clearRect(0,0,1,1);ctx.fillStyle=value;ctx.fillRect(0,0,1,1);const p=ctx.getImageData(0,0,1,1).data;return '#'+[p[3],p[0],p[1],p[2]].map(x=>x.toString(16).padStart(2,'0')).join('');}
                  function style(e){const s=getComputedStyle(e);return {background:color(s.backgroundColor),border:color(s.borderTopColor),borderWidth:parseFloat(s.borderTopWidth),radius:parseFloat(s.borderTopLeftRadius),paddingX:parseFloat(s.paddingLeft),paddingY:parseFloat(s.paddingTop),font:parseFloat(s.fontSize),weight:Number(s.fontWeight),ink:color(s.color)};}
                  const bar=document.querySelector('.settings-tabbar'),tabs=Array.from(bar.querySelectorAll('button')),selected=tabs.find(e=>e.getAttribute('aria-selected')==='true')||tabs[0],inactive=tabs.find(e=>e!==selected);
                  const panel=document.querySelector('.user-settings-panel'),head=panel.querySelector('.user-settings-panel-header'),body=panel.querySelector('.user-settings-panel-body');
                  const root=document.createElement('div');root.className='admin-container admin-shell system-monitor';root.innerHTML='<section class="monitor-panel"><header class="monitor-panel-header"><div><h2>Heading</h2><p>Details</p></div></header><div class="monitor-panel-body"><input class="monitor-input"><button class="monitor-primary">Save</button></div></section>';document.querySelector('#view-container').append(root);
                  const admin=root.querySelector('.monitor-panel'),ah=admin.querySelector('.monitor-panel-header');
                  const out={tabs:{bar:style(bar),active:style(selected),inactive:style(inactive),gap:parseFloat(getComputedStyle(bar).gap),height:bar.getBoundingClientRect().height},settingsPanel:{surface:style(panel),header:style(head),body:style(body),title:style(head.querySelector('h2')),description:style(head.querySelector('p'))},adminPanel:{surface:style(admin),header:style(ah),body:style(admin.querySelector('.monitor-panel-body')),title:style(ah.querySelector('h2')),description:style(ah.querySelector('p')),input:style(admin.querySelector('input')),button:style(admin.querySelector('button'))}};
                  root.remove();return out;
                }""")
                if args.tokens_only:
                    page.close()
                    continue
                await_css = (
                    font_css
                    + f":root{{--safe-area-top:{args.safe_top}px!important;--safe-area-bottom:{args.safe_bottom}px!important;}}"
                )
                page.add_style_tag(content=await_css)
                page.evaluate("document.fonts.ready")
                for view, param in [
                    ("home", None),
                    ("library", None),
                    ("search", None),
                    ("discovery", None),
                    ("playlist", "1"),
                    ("favorites", None),
                    ("public", None),
                    ("community", None),
                    ("party", None),
                    ("artist", "M83"),
                    ("mix", "daily"),
                    ("wrapped", 2026),
                    ("discover_radios", None),
                    ("offline", None),
                    ("settings_user", None),
                    ("profile", "visual"),
                ]:
                    page.evaluate("([v,p])=>loadView(v,p)", [view, param])
                    page.wait_for_timeout(400)
                    name = f"{theme}-{scheme}-{view}"
                    page.screenshot(path=str(args.output / f"web-{name}.png"))
                    measurements[name] = page.evaluate(
                        """() => Array.from(document.querySelectorAll('.m-topbar,.home-tab,.quick-pick,.shelf-header,.library-card,.playlist-cover-large,.playlist-title,.playlist-actions,.user-settings-header,.profile-identity')).map(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {class:e.className,x:r.x,y:r.y,w:r.width,h:r.height,font:s.fontSize,weight:s.fontWeight};})"""
                    )
                page.evaluate("() => loadView('home')")
                page.evaluate("() => openDownloadsModal()")
                page.wait_for_selector("#jobs-list .job-item")
                page.screenshot(path=str(args.output / f"web-{theme}-{scheme}-downloads.png"))
                page.evaluate("() => closeDownloadsModal()")
                page.evaluate("() => showCollaboratorsModal('1')")
                page.wait_for_selector(".collaborators-modal")
                page.screenshot(path=str(args.output / f"web-{theme}-{scheme}-collaborators.png"))
                page.evaluate("() => closeModal()")
                page.evaluate(
                    "async()=>{const c=await import('/assets/js/modules/collections.js?v=visual');await c.previewImport('https://open.spotify.com/playlist/fixture');}"
                )
                page.wait_for_selector("#collection-import")
                page.screenshot(path=str(args.output / f"web-{theme}-{scheme}-import.png"))
                page.evaluate("() => closeModal()")
                page.evaluate(
                    """async()=>{const s=await import('/assets/js/modules/state.js?v=visual'),p=await import('/assets/js/modules/player.js?v=visual'); const track={id:1,db_id:1,title:'Midnight City',artist:'M83',thumbnail:'/fixture/art/1.png',duration:240};s.setCurrentTrack(track);s.setIsPlaying(false);Object.defineProperty(s.audio,'duration',{configurable:true,value:240});s.audio.volume=.7;s.audio.dispatchEvent(new Event('loadedmetadata'));s.audio.dispatchEvent(new Event('timeupdate'));p.updatePlayerUI(track);window.toggleFullscreenPlayer();document.activeElement?.blur();}"""
                )
                page.wait_for_timeout(450)
                page.screenshot(path=str(args.output / f"web-{theme}-{scheme}-player.png"))
                page.goto(f"http://127.0.0.1:8765/login?theme={theme}")
                page.add_style_tag(content=await_css)
                page.evaluate("document.fonts.ready")
                page.wait_for_timeout(400)
                page.screenshot(path=str(args.output / f"web-{theme}-{scheme}-login.png"))
                page.close()
        browser.close()
    (args.output / "web-measurements.json").write_text(json.dumps(measurements, indent=2) + "\n")
    (args.output / "theme-tokens.json").write_text(json.dumps(tokens, indent=2) + "\n")
    if args.write_theme_tokens:
        (Path(__file__).parent / "app/src/main/assets/native/themes.json").write_text(
            json.dumps(tokens, indent=2) + "\n"
        )
    if failures:
        raise RuntimeError("Reference frontend errors: " + "; ".join(sorted(set(failures))))
    print(
        "Read CSS tokens for three themes in dark/light mode."
        if args.tokens_only
        else "Captured three themes in dark/light mode against the loopback fixture."
    )


if __name__ == "__main__":
    capture()
