import assert from 'node:assert/strict';
import {selectSimilarPixels, selectionRuns} from '../src/utils/pet-color-selection.mjs';
const width = 7, height = 3;
const data = new Uint8ClampedArray(width * height * 4);
for (let i=0; i<width*height; i++) data.set([20,20,20,255],i*4);
for (const [i,c] of [[0,240],[1,244],[2,250],[7,240],[14,240],[5,240],[6,240]]) data.set([c,c,c,255],i*4);
let mask=selectSimilarPixels(data,width,height,{x:0,y:0},2);
assert.deepEqual(selectionRuns(mask),[[0,2],[7,1],[14,1]]); // separate white island stays protected
mask=selectSimilarPixels(data,width,height,{x:0,y:0},5);
assert.deepEqual(selectionRuns(mask),[[0,3],[7,1],[14,1]]); // tolerance expands selection
mask=selectSimilarPixels(data,width,height,{x:0,y:0,bounds:[0,0,.2,.3]},20);
assert.deepEqual(selectionRuns(mask),[[0,2]]); // rectangle protects same connected color outside it
mask=selectSimilarPixels(data,width,height,{x:1,y:0},0);
assert.deepEqual(selectionRuns(mask),[[5,2]]); // edge coordinate is clamped safely
const copy=data.slice(); data[3]=0;
assert.deepEqual(selectionRuns(selectSimilarPixels(data,width,height,{x:0,y:0},100)),[]);
assert.deepEqual(copy.subarray(4),data.subarray(4)); // selection never edits the source
assert.deepEqual(selectionRuns(new Uint8Array([0,1,1,0,1])),[[1,2],[4,1]]);
console.log('PASS: connected color selection, tolerance, bounded rectangle, transparent seeds, edge coordinates and exact runs.');
