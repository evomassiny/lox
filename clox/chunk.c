#include "chunk.h"
#include "memory.h"
#include "value.h"
#include "vm.h"
#include <stdio.h>
#include <stdlib.h>

// set chunk field, doesn't allocate
void initChunk(Chunk *chunk) {
  chunk->count = 0;
  chunk->capacity = 0;
  chunk->code = NULL;
  chunk->lines = NULL;
  initValueArray(&chunk->constants);
}

// append byte to chunk, re-allocate if needed.
void writeChunk(Chunk *chunk, uint8_t byte, int line) {
  if (chunk->capacity < chunk->count + 1) {
    int oldCapacity = chunk->capacity;
    chunk->capacity = GROW_CAPACITY(oldCapacity);
    // increase data|opcode array
    chunk->code =
        GROW_ARRAY(uint8_t, chunk->code, oldCapacity, chunk->capacity);
    // increase line nb array
    chunk->lines = GROW_ARRAY(int, chunk->lines, oldCapacity, chunk->capacity);
  }
  chunk->code[chunk->count] = byte;
  chunk->lines[chunk->count] = line;
  chunk->count++;
}

void freeChunk(Chunk *chunk) {
  FREE_ARRAY(uint8_t, chunk->code, chunk->capacity);
  FREE_ARRAY(int, chunk->lines, chunk->capacity);
  freeValueArray(&chunk->constants);
  initChunk(chunk);
}

// append a constant value, and return its index.
int addConstant(Chunk *chunk, Value value) {
  push(value); // add the value so the GC can mark it,
  // if it is triggered while executing writeValueArray()
  writeValueArray(&chunk->constants, value);
  pop();
  return chunk->constants.count - 1;
}
