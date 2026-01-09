# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build the project
./gradlew build

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.example.bettersketch.ExampleUnitTest"

# Clean build
./gradlew clean
```

## Project Overview

BetterSketch is an Android drawing application (Kotlin) that supports freehand sketching with advanced curve fitting and editing capabilities. The app runs on Android 7.0+ (API 24) targeting API 36.

## Architecture

### Core Components

**DrawingView** (`DrawingView.kt`) - Main custom view handling all drawing and gesture interaction. Implements three states:
- `NORMAL_DRAWING` - Regular stroke creation
- `CHOSEN_STROKE_IN_NORMAL_MODE` - A stroke is selected for editing
- `STROKE_EDITING` - Full editing mode with anchor point manipulation

Uses `CustomGestureDetector` for multi-touch handling (single, double, triple finger gestures).

**Stroke** (`Stroke.kt`) - Data model for drawn paths. Key data structures:
- `originalPoints` - Raw input points preserved for undo
- `unsmoothedPoints` - Working points for editing
- `pointsForDrawing` - Final smoothed output
- Bezier data: `bezierAnchorPoints`, `bezierControlPoints1`, `bezierControlPoints2`
- Supports grouping via `childStrokes`

### Curve Fitting Pipeline

1. **BezierFitter** - Fits cubic Bezier curves to point sequences with fixed anchor positions
2. **BezierUtils** - Bezier curve evaluation, regeneration, and control point manipulation
3. **PolyLineFitter** - Simplifies curves to polylines using Douglas-Peucker with bidirectional greedy segmentation
4. **ShapeFitter** - Detects and fits analytical shapes (Square, Circle, Polynomial)

### Gesture System

**CustomGestureDetector** - Handles multi-finger gestures with callbacks for:
- Single/double tap, single/two/three finger drag
- Scale and rotate from two-finger gestures

**ButtonAugmentedGestureHelper** - Allows button-press + touch combinations to trigger special gesture modes (e.g., holding "add anchor" button while touching canvas).

### Utility Classes

- **GeometryUtils** (`Utils.kt`) - Vector math, matrix operations, golden section search
- **SquareFitter**, **CircleFitter**, **PolynomFitter** - Individual shape fitting algorithms

## Code Style Notes

From the codebase comments:
- Prefer keeping function call parameters on the same line
- Minimize lines for parameter declarations
- Don't use redundant named parameters (avoid `myFunction(s = s, b = b)`)
- Call utility functions directly (e.g., `GeometryUtils.evaluateCubicBezier()`) rather than creating passthrough wrappers
