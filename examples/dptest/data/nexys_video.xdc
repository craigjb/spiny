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
set_false_path -from [get_ports { HPD }]
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

set_property -dict { \
  PACKAGE_PIN N15 \
  IOSTANDARD LVCMOS33 \
} [get_ports { HPD }];

set_property -dict { \
  PACKAGE_PIN AA9 \
  IOSTANDARD LVDS_25 \
} [get_ports { AUX_P }];
set_property -dict { \
  PACKAGE_PIN AB10 \
  IOSTANDARD LVDS_25 \
} [get_ports { AUX_N }];

set_property -dict { \
  PACKAGE_PIN AA10 \
  IOSTANDARD LVDS_25 \
} [get_ports { UNUSED_P }];
set_property -dict { \
  PACKAGE_PIN AA11 \
  IOSTANDARD LVDS_25 \
} [get_ports { UNUSED_N }];


set_property -dict { \
  PACKAGE_PIN AB22 \
  IOSTANDARD LVCMOS33 \
} [get_ports { DBG_AUX_FILTERED }];
set_property -dict { \
  PACKAGE_PIN AB21 \
  IOSTANDARD LVCMOS33 \
} [get_ports { DBG_AUX_WRITE_EN }];
