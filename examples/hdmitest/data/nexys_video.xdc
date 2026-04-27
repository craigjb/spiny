###########################################################
# Timing                                                  #
###########################################################

# 100 MHz clock input on board
create_clock \
  -period 10.0 \
  -name SYS_CLK \
  [get_ports SYS_CLK]

set_false_path -from [get_ports { CPU_RESET_N }]

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
  PACKAGE_PIN T1 \
  IOSTANDARD TMDS_33 \
} [get_ports { HDMI_clk_p }];
set_property -dict { \
  PACKAGE_PIN U1 \
  IOSTANDARD TMDS_33 \
} [get_ports { HDMI_clk_n }];

set_property -dict { \
  PACKAGE_PIN W1 \
  IOSTANDARD TMDS_33 \
} [get_ports { HDMI_data_0_p }];
set_property -dict { \
  PACKAGE_PIN Y1 \
  IOSTANDARD TMDS_33 \
} [get_ports { HDMI_data_0_n }];

set_property -dict { \
  PACKAGE_PIN AA1 \
  IOSTANDARD TMDS_33 \
} [get_ports { HDMI_data_1_p }];
set_property -dict { \
  PACKAGE_PIN AB1 \
  IOSTANDARD TMDS_33 \
} [get_ports { HDMI_data_1_n }];

set_property -dict { \
  PACKAGE_PIN AB3 \
  IOSTANDARD TMDS_33 \
} [get_ports { HDMI_data_2_p }];
set_property -dict { \
  PACKAGE_PIN AB2 \
  IOSTANDARD TMDS_33 \
} [get_ports { HDMI_data_2_n }];

###########################################################
# Config Voltage                                          #
###########################################################
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
