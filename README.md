
## SunshineWear
### Project 6 "Go Ubiquitous" of [Udacity Android Developer Nanodegree](https://www.udacity.com/course/android-developer-nanodegree--nd801)

Sunshine weather app with additional Android Wear module which provides custom WatchFace for android smart watches.

### Install
```
$ git clone https://github.com/seliverstov/SunshineWear
$ cd SunshineWear
```
Go to `app/`, open `build.gradle`file in text editor and put your api key to. 

```
buildTypes.each {
        it.buildConfigField 'String', 'OPEN_WEATHER_MAP_API_KEY', '"REPLACE_WITH_YOUR_OWN_API_KEY"'
    }
```
then return to project's root folder and run
```
$ gradle installDebug
```
###License

The contents of this repository are covered under the [MIT License](http://choosealicense.com/licenses/mit/).
