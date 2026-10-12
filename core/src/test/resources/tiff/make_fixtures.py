import sys, struct, zlib
from PIL import Image, TiffImagePlugin
out = sys.argv[1]
W, H = 50, 30
def f(x, y): return (x * 5 % 256, y * 8 % 256, (x + y) * 2 % 256)
rgb = Image.new('RGB', (W, H))
for x in range(W):
    for y in range(H): rgb.putpixel((x, y), f(x, y))
TiffImagePlugin.WRITE_LIBTIFF = True
rgb.save(out + '/lzw_rgb.tif', compression='tiff_lzw', tiffinfo={278: 7})
rgba = rgb.copy(); rgba.putalpha(200)
rgba.save(out + '/deflate_rgba_predictor.tif', compression='tiff_adobe_deflate', tiffinfo={317: 2, 278: 8})
rgb.convert('L').save(out + '/packbits_gray.tif', compression='packbits')
rgb.save(out + '/jpeg_rgb.tif', compression='jpeg', quality=95)
rgb.quantize(16).save(out + '/palette.tif', compression='tiff_lzw')

# Hand-written tiled BigTIFF, big-endian? No: BigTIFF little-endian, deflate tiles with predictor,
# plus a half-size overview IFD, to test tiles, BigTIFF and overview choice.
def tiled_ifd(img, tile, entries_extra=()):
    w, h = img.size
    tiles = []
    for ty in range(0, h, tile):
        for tx in range(0, w, tile):
            raw = bytearray()
            for y in range(ty, ty + tile):
                row = bytearray()
                for x in range(tx, tx + tile):
                    row += bytes(img.getpixel((x, y))) if x < w and y < h else b'\0\0\0'
                # horizontal predictor
                for i in range(len(row) - 1, 2, -1): row[i] = (row[i] - row[i - 3]) & 0xFF
                raw += row
            tiles.append(zlib.compress(bytes(raw)))
    return w, h, tiles
def bigtiff(path, images, tile=16):
    data = bytearray(b'II' + struct.pack('<HHHQ', 43, 8, 0, 0))
    ifd_positions = []
    blobs = []
    for img, sub in images:
        w, h, tiles = tiled_ifd(img, tile)
        offs = []
        for t in tiles:
            offs.append(len(data)); data += t
        entries = [(254, 4, [sub]), (256, 4, [w]), (257, 4, [h]), (258, 3, [8, 8, 8]), (259, 3, [8]), (262, 3, [2]),
                   (277, 3, [3]), (284, 3, [1]), (317, 3, [2]), (322, 3, [tile]), (323, 3, [tile]),
                   (324, 16, offs), (325, 16, [len(t) for t in tiles])]
        blobs.append(entries)
    # write IFDs
    prev_next_pos = 8
    for entries in blobs:
        # arrays that don't fit go before the IFD
        prepared = []
        for tag, typ, vals in entries:
            size = {3: 2, 4: 4, 16: 8}[typ]
            fmt = {3: '<H', 4: '<I', 16: '<Q'}[typ]
            raw = b''.join(struct.pack(fmt, v) for v in vals)
            if len(raw) <= 8:
                prepared.append((tag, typ, len(vals), raw.ljust(8, b'\0')))
            else:
                pos = len(data); data += raw
                prepared.append((tag, typ, len(vals), struct.pack('<Q', pos)))
        if len(data) % 2: data += b'\0'
        pos = len(data)
        struct.pack_into('<Q', data, prev_next_pos, pos)
        data += struct.pack('<Q', len(prepared))
        for tag, typ, n, val in sorted(prepared):
            data += struct.pack('<HHQ', tag, typ, n) + val
        prev_next_pos = len(data)
        data += struct.pack('<Q', 0)
    open(path, 'wb').write(data)
half = Image.new('RGB', (25, 15))
for x in range(25):
    for y in range(15): half.putpixel((x, y), f(2 * x, 2 * y))
bigtiff(out + '/bigtiff_tiled_overview.tif', [(rgb, 0), (half, 1)])
