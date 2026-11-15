from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
BG = '#F5F1E8'
FG = '#4545B8'
out = ROOT / 'design/icons'
svg = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="%s"/><g fill="none" stroke="%s" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"><rect x="23" y="27" width="62" height="44" rx="8"/><path d="M54 71v8M41 79h26"/><path stroke-width="7" d="M42 53v7M54 43v17M66 49v11"/></g></svg>''' % (BG, FG)
(out/'hyperaudio.svg').write_text(svg, encoding='utf-8')
scale=12
im=Image.new('RGB',(108*scale,108*scale),BG)
d=ImageDraw.Draw(im)
def line(points,width):
    pts=[(int(x*scale),int(y*scale)) for x,y in points]
    w=int(width*scale)
    d.line(pts,fill=FG,width=w)
    r=w/2
    for x,y in (pts[0],pts[-1]):d.ellipse((x-r,y-r,x+r,y+r),fill=FG)
d.rounded_rectangle((23*scale,27*scale,85*scale,71*scale),radius=8*scale,outline=FG,width=int(4.5*scale))
line([(54,71),(54,79)],4.5);line([(41,79),(67,79)],4.5)
for x,top in [(42,53),(54,43),(66,49)]:line([(x,top),(x,60)],7)
im=im.resize((1024,1024),Image.Resampling.LANCZOS)
im.save(out/'hyperaudio-cream-indigo.png')
assets=ROOT/'windows/HyperAudio.Rust/assets';assets.mkdir(parents=True,exist_ok=True)
rounded = im.convert('RGBA')
mask = Image.new('L', (4096, 4096), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, 4095, 4095), radius=820, fill=255)
rounded.putalpha(mask.resize(im.size, Image.Resampling.LANCZOS))
rounded.save(out/'hyperaudio-windows-rounded.png')
rounded.save(assets/'app.png')
rounded.save(assets/'app.ico',sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
(assets/'app.rc').write_text('''1 ICON "app.ico"
1 VERSIONINFO
FILEVERSION 0,6,1,0
PRODUCTVERSION 0,6,1,0
FILEFLAGSMASK 0x3fL
FILEFLAGS 0x0L
FILEOS 0x40004L
FILETYPE 0x1L
FILESUBTYPE 0x0L
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040904b0"
    BEGIN
      VALUE "FileDescription", "HyperAudio Bridge"
      VALUE "FileVersion", "0.6.1.0"
      VALUE "ProductName", "HyperAudio Bridge"
      VALUE "ProductVersion", "0.6.1"
      VALUE "OriginalFilename", "hyperaudio.exe"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x409, 1200
  END
END
''',encoding='utf-8')
res=ROOT/'android/app/src/main/res'
path='M31,27 L77,27 Q85,27 85,35 L85,63 Q85,71 77,71 L31,71 Q23,71 23,63 L23,35 Q23,27 31,27 M54,71 L54,79 M41,79 L67,79'
def vector(color, foreground=False):
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"><path android:pathData="{path}" android:fillColor="@android:color/transparent" android:strokeColor="{color}" android:strokeWidth="4.5" android:strokeLineCap="round" android:strokeLineJoin="round"/><path android:pathData="M42,53 L42,60 M54,43 L54,60 M66,49 L66,60" android:fillColor="@android:color/transparent" android:strokeColor="{color}" android:strokeWidth="7" android:strokeLineCap="round"/></vector>'''
(res/'drawable/ic_launcher_foreground.xml').write_text(vector(FG),encoding='utf-8')
(res/'drawable/ic_bridge.xml').write_text(vector('#FFFFFFFF'),encoding='utf-8')
(res/'drawable/ic_computer.xml').write_text(vector('#FFFFFFFF'),encoding='utf-8')
(res/'drawable/ic_app.xml').write_text(vector(FG),encoding='utf-8')
for folder in ['mipmap-anydpi-v26','mipmap-anydpi']:(res/folder).mkdir(exist_ok=True)
(res/'mipmap-anydpi-v26/ic_launcher.xml').write_text('''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/icon_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/><monochrome android:drawable="@drawable/ic_launcher_foreground"/></adaptive-icon>''',encoding='utf-8')
(res/'mipmap-anydpi/ic_launcher.xml').write_text(vector(FG),encoding='utf-8')
(res/'values/icon_colors.xml').write_text('<resources><color name="icon_background">#F5F1E8</color></resources>',encoding='utf-8')
print('Generated flat-color Android adaptive icon, SVG, PNG and Windows ICO')
