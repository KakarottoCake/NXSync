TARGET      := sys-savesync
BUILD       := build
SOURCES     := src
INCLUDES    := include
DATA        :=
ROMFS       :=
PORTLIBS    := $(DEVKITPRO)/portlibs/switch
LIBNX       := $(DEVKITPRO)/libnx
APP_TITLE   := NXSync Save Sync
APP_AUTHOR  := NXSync
APP_VERSION := 1.0.0

ARCH        := -march=armv8-a+crc+crypto -mtune=cortex-a57 -mtp=soft -fPIE
CFLAGS      := -g -Wall -Wextra -O2 -ffunction-sections $(ARCH) $(DEFINES)
CFLAGS      += $(INCLUDE) -D__SWITCH__
CXXFLAGS    := $(CFLAGS) -std=gnu++17 -fno-exceptions -fno-rtti
ASFLAGS     := -g $(ARCH)
LDFLAGS     := -specs=$(DEVKITPRO)/libnx/switch.specs -g $(ARCH) \
               -Wl,-Map,$(notdir $*.map)
LIBS        := -lcurl -lmbedtls -lmbedx509 -lmbedcrypto -lminizip -lz -lnx

LIBDIRS     := $(PORTLIBS) $(LIBNX)

ifneq ($(BUILD),$(notdir $(CURDIR)))
export OUTPUT := $(CURDIR)/$(TARGET)
export TOPDIR := $(CURDIR)
export APP_JSON := $(CURDIR)/npdm.json
export VPATH  := $(foreach dir,$(SOURCES),$(CURDIR)/$(dir))
export DEPSDIR := $(CURDIR)/$(BUILD)
CFILES       := $(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.c)))
CPPFILES     := $(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.cpp)))
export LD := aarch64-none-elf-g++
export OFILES := $(CPPFILES:.cpp=.o) $(CFILES:.c=.o)
export INCLUDE := $(foreach dir,$(INCLUDES),-I$(CURDIR)/$(dir)) \
                  $(foreach dir,$(LIBDIRS),-I$(dir)/include) \
                  -I$(CURDIR)/$(BUILD)
export LIBPATHS := $(foreach dir,$(LIBDIRS),-L$(dir)/lib)

.PHONY: all clean
all: $(BUILD)
	@$(MAKE) --no-print-directory -C $(BUILD) -f $(CURDIR)/Makefile
$(BUILD):
	@mkdir -p $@
clean:
	@rm -fr $(BUILD) $(TARGET).nsp $(TARGET).elf $(TARGET).nacp
else
DEPENDS := $(OFILES:.o=.d)
all: $(OUTPUT).nsp
$(OUTPUT).nsp: $(OUTPUT).elf $(APP_JSON)
$(OUTPUT).elf: $(OFILES)
-include $(DEPENDS)
include $(DEVKITPRO)/libnx/switch_rules
endif
