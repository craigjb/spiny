###########################################################
# Timing                                                  #
###########################################################

# 100 MHz clock input on board
create_clock \
  -period 10.0 \
  -name SYS_CLK \
  [get_ports SYS_CLK]

# internal BSCANE2 JTAG up to 50 MHz
create_clock \
  -period 20.000 \
  -name JTAG_CLK \
  [get_pins -hierarchical *jtagTap/TCK]

# clocks are not related
set_clock_groups \
  -asynchronous \
  -group [get_clocks -include_generated_clocks SYS_CLK] \
  -group [get_clocks -include_generated_clocks JTAG_CLK]

set_false_path -from [get_ports { CPU_RESET_N }]
set_false_path -to [get_ports { LEDS[*] }]

###########################################################
# Pins                                                    #
###########################################################
set_property -dict { \
  PACKAGE_PIN E3 \
  IOSTANDARD LVCMOS33 \
} [get_ports { SYS_CLK }];

set_property -dict { \
  PACKAGE_PIN C12 \
  IOSTANDARD LVCMOS33 \
} [get_ports { CPU_RESET_N }];

# Multi-color LEDs for status bits
set_property -dict { \
  PACKAGE_PIN R11 \
  IOSTANDARD LVCMOS33 \
} [get_ports { INIT_DONE }];
set_property -dict { \
  PACKAGE_PIN N16 \
  IOSTANDARD LVCMOS33 \
} [get_ports { INIT_ERROR }];
set_property -dict { \
  PACKAGE_PIN M16 \
  IOSTANDARD LVCMOS33 \
} [get_ports { PLL_LOCKED }];
set_property -dict { \
  PACKAGE_PIN N15 \
  IOSTANDARD LVCMOS33 \
} [get_ports { PLL_NOT_LOCKED }];

# LEDS[15:0]
set_property -dict { \
  PACKAGE_PIN H17 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[0] }];
set_property -dict { \
  PACKAGE_PIN K15 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[1] }];
set_property -dict { \
  PACKAGE_PIN J13 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[2] }];
set_property -dict { \
  PACKAGE_PIN N14 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[3] }];
set_property -dict { \
  PACKAGE_PIN R18 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[4] }];
set_property -dict { \
  PACKAGE_PIN V17 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[5] }];
set_property -dict { \
  PACKAGE_PIN U17 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[6] }];
set_property -dict { \
  PACKAGE_PIN U16 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[7] }];
set_property -dict { \
  PACKAGE_PIN V16 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[8] }];
set_property -dict { \
  PACKAGE_PIN T15 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[9] }];
set_property -dict { \
  PACKAGE_PIN U14 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[10] }];
set_property -dict { \
  PACKAGE_PIN T16 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[11] }];
set_property -dict { \
  PACKAGE_PIN V15 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[12] }];
set_property -dict { \
  PACKAGE_PIN V14 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[13] }];
set_property -dict { \
  PACKAGE_PIN V12 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[14] }];
set_property -dict { \
  PACKAGE_PIN V11 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[15] }];

# DDR2
set_property INTERNAL_VREF 0.9 [get_iobanks 34]
set_property -dict { \
  PACKAGE_PIN R7 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[0] }];
set_property -dict { \
  PACKAGE_PIN V6 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[1] }];
set_property -dict { \
  PACKAGE_PIN R8 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[2] }];
set_property -dict { \
  PACKAGE_PIN U7 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[3] }];
set_property -dict { \
  PACKAGE_PIN V7 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[4] }];
set_property -dict { \
  PACKAGE_PIN R6 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[5] }];
set_property -dict { \
  PACKAGE_PIN U6 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[6] }];
set_property -dict { \
  PACKAGE_PIN R5 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[7] }];
set_property -dict { \
  PACKAGE_PIN T5 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[8] }];
set_property -dict { \
  PACKAGE_PIN U3 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[9] }];
set_property -dict { \
  PACKAGE_PIN V5 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[10] }];
set_property -dict { \
  PACKAGE_PIN U4 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[11] }];
set_property -dict { \
  PACKAGE_PIN V4 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[12] }];
set_property -dict { \
  PACKAGE_PIN T4 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[13] }];
set_property -dict { \
  PACKAGE_PIN V1 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[14] }];
set_property -dict { \
  PACKAGE_PIN T3 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dq[15] }];
set_property -dict { \
  PACKAGE_PIN T6 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dm[0] }];
set_property -dict { \
  PACKAGE_PIN U1 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_dm[1] }];
set_property -dict { \
  PACKAGE_PIN U9 \
  IOSTANDARD DIFF_SSTL18_II \
} [get_ports { dram_dqsP[0] }];
set_property -dict { \
  PACKAGE_PIN V9 \
  IOSTANDARD DIFF_SSTL18_II \
} [get_ports { dram_dqsN[0] }];
set_property -dict { \
  PACKAGE_PIN U2 \
  IOSTANDARD DIFF_SSTL18_II \
} [get_ports { dram_dqsP[1] }];
set_property -dict { \
  PACKAGE_PIN V2 \
  IOSTANDARD DIFF_SSTL18_II \
} [get_ports { dram_dqsN[1] }];
set_property -dict { \
  PACKAGE_PIN M4 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[0] }];
set_property -dict { \
  PACKAGE_PIN P4 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[1] }];
set_property -dict { \
  PACKAGE_PIN M6 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[2] }];
set_property -dict { \
  PACKAGE_PIN T1 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[3] }];
set_property -dict { \
  PACKAGE_PIN L3 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[4] }];
set_property -dict { \
  PACKAGE_PIN P5 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[5] }];
set_property -dict { \
  PACKAGE_PIN M2 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[6] }];
set_property -dict { \
  PACKAGE_PIN N1 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[7] }];
set_property -dict { \
  PACKAGE_PIN L4 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[8] }];
set_property -dict { \
  PACKAGE_PIN N5 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[9] }];
set_property -dict { \
  PACKAGE_PIN R2 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[10] }];
set_property -dict { \
  PACKAGE_PIN K5 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[11] }];
set_property -dict { \
  PACKAGE_PIN N6 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_a[12] }];
set_property -dict { \
  PACKAGE_PIN P2 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_ba[0] }];
set_property -dict { \
  PACKAGE_PIN P3 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_ba[1] }];
set_property -dict { \
  PACKAGE_PIN R1 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_ba[2] }];
set_property -dict { \
  PACKAGE_PIN L6 \
  IOSTANDARD DIFF_SSTL18_II \
} [get_ports { dram_clkP[0] }];
set_property -dict { \
  PACKAGE_PIN L5 \
  IOSTANDARD DIFF_SSTL18_II \
} [get_ports { dram_clkN[0] }];
set_property -dict { \
  PACKAGE_PIN N4 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_rasN }];
set_property -dict { \
  PACKAGE_PIN L1 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_casN }];
set_property -dict { \
  PACKAGE_PIN N2 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_weN }];
set_property -dict { \
  PACKAGE_PIN M1 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_cke[0] }];
set_property -dict { \
  PACKAGE_PIN M3 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_odt[0] }];
set_property -dict { \
  PACKAGE_PIN K6 \
  IOSTANDARD SSTL18_II \
} [get_ports { dram_csN[0] }];

###########################################################
# Config Voltage                                          #
###########################################################
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
