# performance optimized synthesis + place & route
set_property strategy Flow_PerfOptimized_high [get_runs synth_1]
set_property strategy Performance_Explore [get_runs impl_1]

# known issues (harmless) with vivado 2024.1, just noise
set_msg_config -id {Device 21-9320} -new_severity INFO
set_msg_config -id {Device 21-2174} -new_severity INFO

# synthesis warnings, just noise
set_msg_config -id {Synth 8-7080} -new_severity INFO
set_msg_config -id {Synth 8-6014} -new_severity INFO
set_msg_config -id {Synth 8-7129} -new_severity INFO
set_msg_config -id {Synth 8-3936} -new_severity INFO
