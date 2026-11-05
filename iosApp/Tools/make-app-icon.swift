import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// Swipe Cleaner app icon generator. 1024x1024, y-down layout space.
// Usage: make-icon <out.png> <dark|blue>

let args = CommandLine.arguments
guard args.count == 3 else { fatalError("usage: make-icon <out.png> <dark|blue>") }
let out = args[1]
let variant = args[2]

let S: CGFloat = 1024

func rgb(_ r: Int, _ g: Int, _ b: Int, _ a: CGFloat = 1) -> CGColor {
    CGColor(red: CGFloat(r) / 255, green: CGFloat(g) / 255, blue: CGFloat(b) / 255, alpha: a)
}

// Palette (matches SwipeTheme tokens)
let white = rgb(245, 245, 247)
let gray = rgb(142, 142, 147)
let darkGray = rgb(110, 110, 115)
let accent = rgb(10, 132, 255)
let gapDark = rgb(18, 18, 20)
let gapBlue = rgb(9, 78, 170)

let bgTop: CGColor
let bgBottom: CGColor
let broomHeadColor: CGColor
let bristleGapColor: CGColor
let panColor: CGColor
let panEdgeColor: CGColor
let gripColor: CGColor
let streakColor: CGColor
let dustColor: CGColor

switch variant {
case "dark":
    bgTop = rgb(34, 34, 38); bgBottom = rgb(4, 4, 6)
    broomHeadColor = accent; bristleGapColor = gapDark
    panColor = rgb(200, 200, 206); panEdgeColor = rgb(150, 150, 158)
    gripColor = accent
    streakColor = accent
    dustColor = gray
case "blue":
    bgTop = rgb(28, 122, 255); bgBottom = rgb(6, 72, 178)
    broomHeadColor = white; bristleGapColor = gapBlue
    panColor = white; panEdgeColor = rgb(208, 226, 250)
    gripColor = rgb(233, 242, 255)
    streakColor = white
    dustColor = rgb(210, 230, 255)
default: fatalError("variant must be dark|blue")
}

let cs = CGColorSpace(name: CGColorSpace.sRGB)!
guard let ctx = CGContext(
    data: nil, width: Int(S), height: Int(S), bitsPerComponent: 8,
    bytesPerRow: 0, space: cs,
    // Opaque output: app icons must not carry an alpha channel.
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else { fatalError("no context") }

// Flip to y-down
ctx.translateBy(x: 0, y: S)
ctx.scaleBy(x: 1, y: -1)
ctx.setShouldAntialias(true)

// Background gradient
let grad = CGGradient(colorsSpace: cs, colors: [bgTop, bgBottom] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: 0), end: CGPoint(x: 0, y: S), options: [])

func roundedRect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> CGPath {
    CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: r, cornerHeight: r, transform: nil)
}

func fill(_ path: CGPath, _ color: CGColor) {
    ctx.addPath(path); ctx.setFillColor(color); ctx.fillPath()
}

func stroke(_ path: CGPath, _ color: CGColor, _ width: CGFloat) {
    ctx.addPath(path); ctx.setStrokeColor(color); ctx.setLineWidth(width)
    ctx.setLineCap(.round); ctx.strokePath()
}

func line(_ p1: CGPoint, _ p2: CGPoint, _ color: CGColor, _ width: CGFloat) {
    let p = CGMutablePath()
    p.move(to: p1); p.addLine(to: p2)
    stroke(p, color, width)
}

// ---- Dustpan (side view, opening left), slight tilt, sitting on floor y=770
ctx.saveGState()
ctx.translateBy(x: 690, y: 665)
ctx.rotate(by: -4 * .pi / 180)
ctx.translateBy(x: -690, y: -665)
// Scoop: right triangle with a rounded back-top corner
let pan = CGMutablePath()
pan.move(to: CGPoint(x: 552, y: 764)) // front lip
pan.addLine(to: CGPoint(x: 816, y: 764)) // bottom
pan.addCurve(to: CGPoint(x: 840, y: 586),
             control1: CGPoint(x: 838, y: 764), control2: CGPoint(x: 846, y: 660)) // back wall
pan.addLine(to: CGPoint(x: 552, y: 764)) // straight diagonal top edge
pan.closeSubpath()
fill(pan, panColor)
// Diagonal top-edge highlight
line(CGPoint(x: 560, y: 756), CGPoint(x: 830, y: 600), panEdgeColor, 12)
// Flat lip on the ground
line(CGPoint(x: 532, y: 764), CGPoint(x: 828, y: 764), panColor, 26)
// Handle from the back, going up-right, starting inside the pan
line(CGPoint(x: 826, y: 616), CGPoint(x: 908, y: 452), panColor, 30)
// Grip
line(CGPoint(x: 882, y: 508), CGPoint(x: 908, y: 452), gripColor, 46)
ctx.restoreGState()

// ---- Broom: drawn vertical in local space, rotated so bristles point down-right
ctx.saveGState()
ctx.translateBy(x: 415, y: 435)
ctx.rotate(by: -37 * .pi / 180)
// Handle
fill(roundedRect(-16, -340, 32, 300, 16), white)
// Collar
fill(roundedRect(-50, -52, 100, 44, 12), darkGray)
// Bristles: trapezoid flaring downward
let bristles = CGMutablePath()
bristles.move(to: CGPoint(x: -50, y: -8))
bristles.addLine(to: CGPoint(x: 50, y: -8))
bristles.addLine(to: CGPoint(x: 104, y: 158))
bristles.addCurve(to: CGPoint(x: -104, y: 158), control1: CGPoint(x: 40, y: 172), control2: CGPoint(x: -40, y: 172))
bristles.closeSubpath()
fill(bristles, broomHeadColor)
// Bristle gaps
line(CGPoint(x: -20, y: 14), CGPoint(x: -46, y: 150), bristleGapColor, 10)
line(CGPoint(x: 0, y: 14), CGPoint(x: 0, y: 154), bristleGapColor, 10)
line(CGPoint(x: 20, y: 14), CGPoint(x: 46, y: 150), bristleGapColor, 10)
ctx.restoreGState()

// ---- Dust particles heading into the pan
func dot(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ a: CGFloat = 1) {
    fill(CGPath(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2), transform: nil),
         dustColor.copy(alpha: a) ?? dustColor)
}
dot(520, 690, 15, 0.95)
dot(566, 724, 10, 0.8)
dot(488, 736, 8, 0.65)
dot(600, 748, 7, 0.55)

// ---- Swipe speed lines, parallel to the broom handle, trailing up-left
let streaks: [(CGPoint, CGFloat, CGFloat)] = [
    (CGPoint(x: 138, y: 262), 14, 0.95),
    (CGPoint(x: 88, y: 356), 11, 0.6),
    (CGPoint(x: 216, y: 196), 8, 0.35),
]
for (p, w, a) in streaks {
    line(p, CGPoint(x: p.x + 92 * 0.61, y: p.y + 92 * 0.79), streakColor.copy(alpha: a) ?? streakColor, w)
}

// ---- Write PNG
guard let img = ctx.makeImage(),
      let dest = CGImageDestinationCreateWithURL(
          URL(fileURLWithPath: out) as CFURL, UTType.png.identifier as CFString, 1, nil)
else { fatalError("encode failed") }
CGImageDestinationAddImage(dest, img, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("finalize failed") }
print("wrote \(out)")
