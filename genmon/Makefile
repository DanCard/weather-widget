CC = gcc
CFLAGS = -O3 -Wall
TARGET = genmon-weather-bin
SRC = genmon-weather.c

.PHONY: all clean install

all: $(TARGET)

$(TARGET): $(SRC)
	$(CC) $(CFLAGS) $(SRC) -o $(TARGET)

clean:
	rm -f $(TARGET)

install: $(TARGET)
	@echo "Binary is built at $(PWD)/$(TARGET)"
	@echo "To use in XFCE Genmon, set command to: $(PWD)/$(TARGET)"
