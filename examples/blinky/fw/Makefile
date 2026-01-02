default:
	riscv64-linux-gnu-as -march=rv32i -o blinky.out blinky.asm
	riscv64-linux-gnu-objcopy -O binary blinky.out blinky.bin

clean:
	rm -f blinky.out
	rm -f blinky.bin
