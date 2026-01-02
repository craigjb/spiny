.section .text
  .globl _start
_start:
      li x5, 0x1
      li x28, 0x8000
      li x6, 0x10000000

outer:
      li x7, 0x8 # 0x80000

loop0:
      sub x7, x7, x5
      bne x7, x0, loop0
      lw x26, 0x100(x6)

loop1:
      and x27, x26, x28
      srli x28, x28, 1
      bne x27, x0, show_led
      beq x28, x0, reset
      j loop1

show_led:
      sw x27, 0(x6)
      beq x28, x0, reset
      j outer

reset:
      li x28, 0x8000
      j outer
