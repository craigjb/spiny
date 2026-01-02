.section .text
.globl _start
_start:
      li x5, 0x1
      li x6, 0x10000000
      li x28, 0x8000      

outer:
      li x7, 0x100000

loop0:
      sub x7, x7, x5
      bne x7, x0, loop0
      lw x26, 0x100(x6)
      beq x26, x0, clear_leds

find_next_active:
      bne x28, x0, check_bit
      li x28, 0x8000

check_bit:
      and x27, x26, x28
      srli x28, x28, 1
      bne x27, x0, show_led
      j find_next_active

show_led:
      sw x27, 0(x6)
      j outer

clear_leds:
      sw x0, 0(x6)
      j outer
