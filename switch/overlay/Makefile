TARGET      := savesync
BUILD       := build
SOURCES     := source
INCLUDES    :=
PORTLIBS    := $(DEVKITPRO)/portlibs/switch
LIBNX       := $(DEVKITPRO)/libnx
TOPDIR      ?= $(CURDIR)
LIBULTRAHAND_ROOT ?= $(TOPDIR)/../../.tools/libultrahand
include $(LIBULTRAHAND_ROOT)/ultrahand.mk
APP_TITLE   := NXSync
APP_AUTHOR  := NXSync
APP_VERSION := 1.0.0

ARCH        := -march=armv8-a+crc+crypto -mtune=cortex-a57 -mtp=soft -fPIE
CFLAGS      := -g -Wall -O2 -ffunction-sections $(ARCH) $(DEFINES)
CFLAGS      += $(INCLUDE) -D__SWITCH__
CXXFLAGS    := $(CFLAGS) -std=gnu++26 -fno-exceptions -fno-rtti
ASFLAGS     := -g $(ARCH)
LDFLAGS     := -specs=$(DEVKITPRO)/libnx/switch.specs -g $(ARCH)
LIBS        := -lcurl -lz -lminizip -lmbedtls -lmbedx509 -lmbedcrypto -lnx
LIBDIRS     := $(PORTLIBS) $(LIBNX)

ifneq ($(BUILD),$(notdir $(CURDIR)))
export OUTPUT := $(CURDIR)/$(TARGET)
export TOPDIR := $(CURDIR)
export VPATH := $(foreach dir,$(SOURCES),$(CURDIR)/$(dir))
export DEPSDIR := $(CURDIR)/$(BUILD)
CPPFILES := $(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.cpp)))
CFILES := $(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.c)))
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
	@rm -fr $(BUILD) $(TARGET).elf $(TARGET).ovl
else
DEPENDS := $(OFILES:.o=.d)
all: $(OUTPUT).ovl
$(OUTPUT).ovl: $(OUTPUT).elf
	@elf2nro $< $@
$(OUTPUT).elf: $(OFILES)
-include $(DEPENDS)
include $(DEVKITPRO)/libnx/switch_rules
endif
