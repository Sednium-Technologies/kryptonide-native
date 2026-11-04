import urllib.request
import os

url = "https://github.com/rsms/inter/raw/master/docs/font-files/Inter-Regular.woff2"
# TTF url for Inter:
url = "https://github.com/google/fonts/raw/main/ofl/inter/Inter%5Bslnt%2Cwght%5D.ttf"

os.makedirs("app/src/main/res/font", exist_ok=True)
urllib.request.urlretrieve(url, "app/src/main/res/font/inter_regular.ttf")

url2 = "https://github.com/google/fonts/raw/main/ofl/poppins/Poppins-Regular.ttf"
urllib.request.urlretrieve(url2, "app/src/main/res/font/poppins_regular.ttf")

url3 = "https://github.com/google/fonts/raw/main/ofl/poppins/Poppins-Medium.ttf"
urllib.request.urlretrieve(url3, "app/src/main/res/font/poppins_medium.ttf")

url4 = "https://github.com/google/fonts/raw/main/ofl/poppins/Poppins-SemiBold.ttf"
urllib.request.urlretrieve(url4, "app/src/main/res/font/poppins_semibold.ttf")

print("Fonts downloaded.")
