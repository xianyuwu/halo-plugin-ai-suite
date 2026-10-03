export type ColorSelection = { x: number; y: number; bounds?: [number, number, number, number] };
export function selectSimilarPixels(data: Uint8ClampedArray, width: number, height: number, selection: ColorSelection, tolerance: number): Uint8Array;
export function selectionRuns(mask: Uint8Array): number[][];
