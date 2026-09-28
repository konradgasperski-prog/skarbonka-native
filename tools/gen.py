#!/usr/bin/env python3
"""Generates the Watch Face Format project (tarcza/) from rendered assets."""
import json, math, os, shutil, sys
from xml.sax.saxutils import escape

SRC = sys.argv[1] if len(sys.argv) > 1 else 'assets'
DST = sys.argv[2] if len(sys.argv) > 2 else 'tarcza'
W = 450; C = W / 2; RA = 208; TH = 8

meta = json.load(open(os.path.join(SRC, 'meta.json')))
GH = meta['gh']

PALETTES = ['Pomarańcz', 'Magenta', 'Turkus', 'Indygo', 'Czerwień', 'Błękit', 'Bursztyn', 'Fiolet']
# name, WFF start/end angle (0 = 12 o'clock, clockwise), reverse fill, color, default provider, type, touch box
SLOTS = [
    dict(id=0, a0=288, a1=338, rev=False, col='#FFFFB020', prov='STEP_COUNT', ptype='SHORT_TEXT', box=(8, 40, 112, 150), name='slot_lg'),
    dict(id=1, a0=22, a1=72, rev=True, col='#FFFF6FB5', prov='HEART_RATE', ptype='SHORT_TEXT', box=(330, 40, 112, 150), name='slot_pg'),
    dict(id=2, a0=202, a1=252, rev=True, col='#FF2FD6A6', prov='EMPTY', ptype='EMPTY', box=(8, 260, 112, 150), name='slot_ld'),
    dict(id=3, a0=108, a1=158, rev=False, col='#FF6C8BFF', prov='WATCH_BATTERY', ptype='RANGED_VALUE', box=(330, 260, 112, 150), name='slot_pd'),
]
CIRC = dict(id=4, cx=225, cy=381, r=22, col='#FF4BE08A', prov='EMPTY', ptype='EMPTY', name='slot_dol')

FRAC = ('clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / '
        '([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1)')


def pt(deg, r=RA):
    a = math.radians(deg)
    return C + r * math.sin(a), C - r * math.cos(a)


def arc(a0, a1, color, thick=TH, transform=None, cx=C, cy=C, r=RA):
    t = f'\n            <Transform target="{transform[0]}" value="{escape(transform[1])}"/>' if transform else ''
    return f'''          <Arc centerX="{cx:.0f}" centerY="{cy:.0f}" width="{2*r:.0f}" height="{2*r:.0f}" startAngle="{a0:.1f}" endAngle="{a1:.1f}">{t}
            <Stroke color="{color}" thickness="{thick}" cap="ROUND"/>
          </Arc>'''


def side_slot(s):
    a0, a1, col = s['a0'], s['a1'], s['col']
    span = a1 - a0
    start = a1 if s['rev'] else a0
    end = a0 if s['rev'] else a1
    sgn = 1 if s['rev'] else -1           # direction pointing away from the arc at the start tip
    ico_deg = start + sgn * 8.5
    txt_deg = end - sgn * 11
    ix, iy = pt(ico_deg)
    tx_, ty_ = pt(txt_deg)
    # keep value text upright
    rot = txt_deg if (txt_deg % 360) > 270 or (txt_deg % 360) < 90 else txt_deg - 180
    if s['rev']:
        fill = arc(a1, a1, col, transform=('startAngle', f'{a1} - {span} * {FRAC}'))
    else:
        fill = arc(a0, a0, col, transform=('endAngle', f'{a0} + {span} * {FRAC}'))
    icon = lambda: f'''        <PartImage x="{ix-11:.0f}" y="{iy-11:.0f}" width="22" height="22" tintColor="{col}">
          <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/>
        </PartImage>'''
    text = lambda: f'''        <PartText x="{tx_-45:.0f}" y="{ty_-14:.0f}" width="90" height="28" angle="{rot:.1f}">
          <Text align="CENTER" ellipsis="TRUE">
            <Font family="SYNC_TO_DEVICE" size="19" weight="BOLD" color="#FFFFFFFF"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font>
          </Text>
        </PartText>'''
    bx, by, bw, bh = s['box']
    return f'''    <ComplicationSlot slotId="{s['id']}" displayName="{s['name']}" x="0" y="0" width="{W}" height="{W}" supportedTypes="RANGED_VALUE SHORT_TEXT" isCustomizable="TRUE">
      <DefaultProviderPolicy defaultSystemProvider="{s['prov']}" defaultSystemProviderType="{s['ptype']}"/>
      <BoundingBox x="{bx}" y="{by}" width="{bw}" height="{bh}"/>
      <Complication type="RANGED_VALUE">
        <PartDraw x="0" y="0" width="{W}" height="{W}">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
{arc(a0, a1, '#26FFFFFF')}
        </PartDraw>
        <PartDraw x="0" y="0" width="{W}" height="{W}">
          <Variant mode="AMBIENT" target="alpha" value="150"/>
{fill}
        </PartDraw>
{icon()}
{text()}
      </Complication>
      <Complication type="SHORT_TEXT">
        <PartDraw x="0" y="0" width="{W}" height="{W}">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
{arc(a0, a1, '#26FFFFFF')}
        </PartDraw>
{icon()}
{text()}
      </Complication>
    </ComplicationSlot>'''


def circle_slot(s):
    cx, cy, r, col = s['cx'], s['cy'], s['r'], s['col']
    box = f'x="{cx-r-8}" y="{cy-r-8}" width="{2*r+16}" height="{2*r+16}"'
    icon = f'''        <PartImage x="{cx-8}" y="{cy-15}" width="16" height="16" tintColor="{col}">
          <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/>
        </PartImage>'''
    text = f'''        <PartText x="{cx-24}" y="{cy}" width="48" height="16">
          <Text align="CENTER" ellipsis="TRUE">
            <Font family="SYNC_TO_DEVICE" size="13" weight="BOLD" color="#FFFFFFFF"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font>
          </Text>
        </PartText>'''
    track = f'''        <PartDraw x="0" y="0" width="{W}" height="{W}">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
{arc(0, 360, '#26FFFFFF', 5, cx=cx, cy=cy, r=r)}
        </PartDraw>'''
    fill = arc(0, 0, col, 5, transform=('endAngle', f'359.9 * {FRAC}'), cx=cx, cy=cy, r=r)
    return f'''    <ComplicationSlot slotId="{s['id']}" displayName="{s['name']}" x="0" y="0" width="{W}" height="{W}" supportedTypes="RANGED_VALUE SHORT_TEXT" isCustomizable="TRUE">
      <DefaultProviderPolicy defaultSystemProvider="{s['prov']}" defaultSystemProviderType="{s['ptype']}"/>
      <BoundingBox {box}/>
      <Complication type="RANGED_VALUE">
{track}
        <PartDraw x="0" y="0" width="{W}" height="{W}">
{fill}
        </PartDraw>
{icon}
{text}
      </Complication>
      <Complication type="SHORT_TEXT">
{track}
{icon}
{text}
      </Complication>
    </ComplicationSlot>'''


def bitmap_font(name, prefix):
    chars = []
    for ch in '0123456789:':
        key = ('g_' if prefix == 'dg' else 'a_') + ch
        res = f"{prefix}_{'colon' if ch == ':' else ch}"
        m = meta['meta'][key]
        chars.append(f'      <Character name="{escape(ch)}" resource="{res}" width="{m["w"]}" height="{m["h"]}"/>')
    return f'    <BitmapFont name="{name}">\n' + '\n'.join(chars) + '\n    </BitmapFont>'


def list_images(prefix, ambient):
    opts = []
    for i in range(len(PALETTES)):
        opts.append(f'''      <ListOption id="{i}">
        <PartImage x="0" y="0" width="{W}" height="{W}">
          <Image resource="{prefix}_{i}"/>
        </PartImage>
      </ListOption>''')
    variant = ('<Variant mode="AMBIENT" target="alpha" value="255"/>' if ambient
               else '<Variant mode="AMBIENT" target="alpha" value="0"/>')
    alpha = '0' if ambient else '255'
    return f'''    <Group name="{prefix}_layer" x="0" y="0" width="{W}" height="{W}" alpha="{alpha}">
      {variant}
      <ListConfiguration id="theme">
{chr(10).join(opts)}
      </ListConfiguration>
    </Group>'''


CLK_Y = 112
watchface = f'''<?xml version="1.0" encoding="utf-8"?>
<WatchFace width="{W}" height="{W}">
  <Metadata key="CLOCK_TYPE" value="DIGITAL"/>
  <Metadata key="PREVIEW_TIME" value="10:08:32"/>
  <UserConfigurations>
    <ListConfiguration id="theme" displayName="cfg_theme" screenReaderText="cfg_theme" defaultValue="3">
{chr(10).join(f'      <ListOption id="{i}" displayName="pal_{i}" screenReaderText="pal_{i}" icon="bg_{i}"/>' for i in range(len(PALETTES)))}
    </ListConfiguration>
    <BooleanConfiguration id="stars" displayName="cfg_stars" screenReaderText="cfg_stars" defaultValue="TRUE"/>
  </UserConfigurations>
  <BitmapFonts>
{bitmap_font('glass', 'dg')}
{bitmap_font('outline', 'da')}
  </BitmapFonts>
  <Scene backgroundColor="#FF000000">
{list_images('bg', False)}
    <BooleanConfiguration id="stars">
      <BooleanOption id="TRUE">
        <PartImage x="0" y="0" width="{W}" height="{W}">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <Image resource="stars"/>
        </PartImage>
      </BooleanOption>
    </BooleanConfiguration>
{list_images('aod', True)}
{chr(10).join(side_slot(s) for s in SLOTS)}
{circle_slot(CIRC)}
    <Group name="clock_glass" x="0" y="0" width="{W}" height="{W}">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <DigitalClock x="30" y="{CLK_Y}" width="{W-60}" height="{GH}">
        <TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER" x="0" y="0" width="{W-60}" height="{GH}">
          <BitmapFont family="glass" size="{GH}"/>
        </TimeText>
      </DigitalClock>
    </Group>
    <Group name="clock_aod" x="0" y="0" width="{W}" height="{W}" alpha="0">
      <Variant mode="AMBIENT" target="alpha" value="255"/>
      <DigitalClock x="30" y="{CLK_Y}" width="{W-60}" height="{GH}">
        <TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER" x="0" y="0" width="{W-60}" height="{GH}">
          <BitmapFont family="outline" size="{GH}"/>
        </TimeText>
      </DigitalClock>
    </Group>
    <PartText x="100" y="{CLK_Y+GH+2}" width="250" height="34">
      <Variant mode="AMBIENT" target="alpha" value="170"/>
      <Text align="CENTER">
        <Font family="SYNC_TO_DEVICE" size="24" weight="BOLD" color="#E6FFFFFF"><Template>%s %s<Parameter expression="[DAY_OF_WEEK_S]"/><Parameter expression="[DAY]"/></Template></Font>
      </Text>
    </PartText>
  </Scene>
</WatchFace>
'''

strings = {
    'app_name': 'Szklane kapsuły',
    'cfg_theme': 'Kolor', 'cfg_stars': 'Gwiazdki',
    'slot_lg': 'Lewy górny pasek', 'slot_pg': 'Prawy górny pasek', 'slot_ld': 'Lewy dolny pasek',
    'slot_pd': 'Prawy dolny pasek', 'slot_dol': 'Kółko na dole',
}
for i, n in enumerate(PALETTES):
    strings[f'pal_{i}'] = n

if os.path.exists(DST):
    shutil.rmtree(DST)
os.makedirs(f'{DST}/res/raw'); os.makedirs(f'{DST}/res/xml'); os.makedirs(f'{DST}/res/values'); os.makedirs(f'{DST}/res/drawable-nodpi')
open(f'{DST}/res/raw/watchface.xml', 'w').write(watchface)
open(f'{DST}/res/values/strings.xml', 'w').write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' +
    '\n'.join(f'  <string name="{k}">{escape(v)}</string>' for k, v in strings.items()) + '\n</resources>\n')
open(f'{DST}/res/xml/watch_face_info.xml', 'w').write('''<?xml version="1.0" encoding="utf-8"?>
<WatchFaceInfo>
  <Preview value="@drawable/preview"/>
  <Category value="CATEGORY_EMPTY"/>
  <AvailableInRetail value="true"/>
  <MultipleInstancesAllowed value="true"/>
  <Editable value="true"/>
</WatchFaceInfo>
''')
open(f'{DST}/AndroidManifest.xml', 'w').write('''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.konrad.tarcza.szklo">
  <uses-feature android:name="android.hardware.type.watch"/>
  <application android:label="@string/app_name" android:hasCode="false" android:icon="@drawable/preview">
    <property android:name="com.google.wear.watchface.format.version" android:value="2"/>
    <property android:name="com.google.wear.watchface.format.publisher" android:value="konrad"/>
  </application>
</manifest>
''')
for f in os.listdir(SRC):
    if f.endswith('.png'):
        shutil.copy(os.path.join(SRC, f), f'{DST}/res/drawable-nodpi/{f}')
print('ok', len(watchface), 'bytes')
