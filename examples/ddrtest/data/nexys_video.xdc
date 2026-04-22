###########################################################
# Timing                                                  #
###########################################################

# 100 MHz clock input on board
create_clock \
  -period 10.0 \
  -name SYS_CLK \
  [get_ports SYS_CLK]

# internal BSCANE2 JTAG up to 50 MHz
# create_clock \
#   -period 20.000 \
#   -name JTAG_CLK \
#   [get_pins -hierarchical *jtagTap/TCK]

# clocks are not related
# set_clock_groups \
#   -asynchronous \
#   -group [get_clocks -include_generated_clocks SYS_CLK] \
#   -group [get_clocks -include_generated_clocks JTAG_CLK]
#
set_false_path -from [get_ports { CPU_RESET_N }]
set_false_path -to [get_ports { LEDS[*] }]

###########################################################
# Pins                                                    #
###########################################################
set_property -dict { \
  PACKAGE_PIN R4 \
  IOSTANDARD LVCMOS33 \
} [get_ports { SYS_CLK }];

set_property -dict { \
  PACKAGE_PIN G4 \
  IOSTANDARD LVCMOS15 \
} [get_ports { CPU_RESET_N }];

# LEDS[7:0]
set_property -dict { \
  PACKAGE_PIN T14 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[0] }];
set_property -dict { \
  PACKAGE_PIN T15 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[1] }];
set_property -dict { \
  PACKAGE_PIN T16 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[2] }];
set_property -dict { \
  PACKAGE_PIN U16 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[3] }];
set_property -dict { \
  PACKAGE_PIN V15 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[4] }];
set_property -dict { \
  PACKAGE_PIN W16 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[5] }];
set_property -dict { \
  PACKAGE_PIN W15 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[6] }];
set_property -dict { \
  PACKAGE_PIN Y13 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[7] }];

# DDR3
set_property INTERNAL_VREF 0.75 [get_iobanks 35]

set_property -dict { \
  PACKAGE_PIN G2 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[0] }];
set_property -dict { \
  PACKAGE_PIN H4 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[1] }];
set_property -dict { \
  PACKAGE_PIN H5 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[2] }];
set_property -dict { \
  PACKAGE_PIN J1 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[3] }];
set_property -dict { \
  PACKAGE_PIN K1 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[4] }];
set_property -dict { \
  PACKAGE_PIN H3 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[5] }];
set_property -dict { \
  PACKAGE_PIN H2 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[6] }];
set_property -dict { \
  PACKAGE_PIN J5 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[7] }];
set_property -dict { \
  PACKAGE_PIN E3 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[8] }];
set_property -dict { \
  PACKAGE_PIN B2 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[9] }];
set_property -dict { \
  PACKAGE_PIN F3 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[10] }];
set_property -dict { \
  PACKAGE_PIN D2 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[11] }];
set_property -dict { \
  PACKAGE_PIN C2 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[12] }];
set_property -dict { \
  PACKAGE_PIN A1 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[13] }];
set_property -dict { \
  PACKAGE_PIN E2 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[14] }];
set_property -dict { \
  PACKAGE_PIN B1 \
  IOSTANDARD SSTL15 \
  IN_TERM UNTUNED_SPLIT_50 \
  SLEW FAST \
} [get_ports { dram_dq[15] }];
set_property -dict { \
  PACKAGE_PIN G3 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_dm[0] }];
set_property -dict { \
  PACKAGE_PIN F1 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_dm[1] }];
set_property -dict { \
  PACKAGE_PIN K2 \
  IOSTANDARD DIFF_SSTL15 \
  SLEW FAST \
} [get_ports { dram_dqsP[0] }];
set_property -dict { \
  PACKAGE_PIN J2 \
  IOSTANDARD DIFF_SSTL15 \
  SLEW FAST \
} [get_ports { dram_dqsN[0] }];
set_property -dict { \
  PACKAGE_PIN E1 \
  IOSTANDARD DIFF_SSTL15 \
  SLEW FAST \
} [get_ports { dram_dqsP[1] }];
set_property -dict { \
  PACKAGE_PIN D1 \
  IOSTANDARD DIFF_SSTL15 \
  SLEW FAST \
} [get_ports { dram_dqsN[1] }];
set_property -dict { \
  PACKAGE_PIN M2 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[0] }];
set_property -dict { \
  PACKAGE_PIN M5 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[1] }];
set_property -dict { \
  PACKAGE_PIN M3 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[2] }];
set_property -dict { \
  PACKAGE_PIN M1 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[3] }];
set_property -dict { \
  PACKAGE_PIN L6 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[4] }];
set_property -dict { \
  PACKAGE_PIN P1 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[5] }];
set_property -dict { \
  PACKAGE_PIN N3 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[6] }];
set_property -dict { \
  PACKAGE_PIN N2 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[7] }];
set_property -dict { \
  PACKAGE_PIN M6 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[8] }];
set_property -dict { \
  PACKAGE_PIN R1 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[9] }];
set_property -dict { \
  PACKAGE_PIN L5 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[10] }];
set_property -dict { \
  PACKAGE_PIN N5 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[11] }];
set_property -dict { \
  PACKAGE_PIN N4 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[12] }];
set_property -dict { \
  PACKAGE_PIN P2 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[13] }];
set_property -dict { \
  PACKAGE_PIN P6 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_a[14] }];
set_property -dict { \
  PACKAGE_PIN L3 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_ba[0] }];
set_property -dict { \
  PACKAGE_PIN K6 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_ba[1] }];
set_property -dict { \
  PACKAGE_PIN L4 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_ba[2] }];
set_property -dict { \
  PACKAGE_PIN P5 \
  IOSTANDARD DIFF_SSTL15 \
  SLEW FAST \
} [get_ports { dram_clkP[0] }];
set_property -dict { \
  PACKAGE_PIN P4 \
  IOSTANDARD DIFF_SSTL15 \
  SLEW FAST \
} [get_ports { dram_clkN[0] }];
set_property -dict { \
  PACKAGE_PIN J4 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_rasN }];
set_property -dict { \
  PACKAGE_PIN K3 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_casN }];
set_property -dict { \
  PACKAGE_PIN L1 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_weN }];
set_property -dict { \
  PACKAGE_PIN J6 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_cke[0] }];
set_property -dict { \
  PACKAGE_PIN K4 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_odt[0] }];
set_property -dict { \
  PACKAGE_PIN G1 \
  IOSTANDARD SSTL15 \
  SLEW FAST \
} [get_ports { dram_resetN }];

###########################################################
# Config Voltage                                          #
###########################################################
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
