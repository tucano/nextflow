/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.util
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.spi.FileSystemProvider

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import groovy.util.logging.Slf4j
import org.codehaus.groovy.runtime.GStringImpl

/**
 * Helper class to get a {@code Kryo} object ready to be used
 */
class KryoHelper {

    static private Class<Path> PATH_CLASS = Paths.get('.').class

    static final Map<Class,Object> serializers

    static {
        serializers = [:]
        serializers.put( PATH_CLASS, PathSerializer )
        serializers.put( GStringImpl, GStringSerializer)
    }

    /**
     * Register a new class - serializer pair
     *
     * @param clazz
     * @param item
     */
    static void register( Class clazz, item = null ) {
        serializers.put( clazz, item )
    }

    /**
     * @return A new instance {@code Kryo} instance
     */
    static Kryo kryo() {
        def result = new Kryo()
        serializers.each { k, v ->
            if( v instanceof Class )
                result.register(k,(Serializer)v.newInstance())

            else if( v instanceof Closure<Serializer> )
                result.register(k, v.call(result))

            else
                result.register(k)
        }
        return result
    }

    /**
     * De-serialize an object stored to a file
     *
     * @param file A {@code Path} reference to a file
     * @return The de-serialized object
     */
    static read( Path file ) {
        Input input = null
        try {
            input = new Input(Files.newInputStream(file))
            return kryo().readClassAndObject(input)
        }
        finally {
            input?.closeQuietly()
        }
    }

    /**
     * De-serialize an object stored to a file
     *
     * @param file A {@code File} reference to a file
     * @return The de-serialized object
     */
    static read( File file ) {
        read(file.toPath())
    }

    /**
     * Serialize an object to the specified file
     *
     * @param object The object to serialize
     * @param toFile A {@code Path} reference to the file where store the object
     */
    static void write( object, Path toFile ) {
        def output = null
        try {
            output = new Output(Files.newOutputStream(toFile) )
            kryo().writeClassAndObject(output, object)
        }
        finally {
            output?.closeQuietly()
        }
    }


    /**
     * Serialize an object to the specified file
     *
     * @param object The object to serialize
     * @param toFile A {@code File} reference to the file where store the object
     */
    static void write( object, File toFile ) {
        write(object,toFile.toPath())
    }

}


/**
 * A Kryo serializer to handler a {@code Path} object
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */

@Slf4j
class PathSerializer extends Serializer<Path> {

    @Override
    void write(Kryo kryo, Output output, Path target) {
        final scheme = target.getFileSystem().provider().getScheme()
        final path = target.toString()
        log.trace "Path serialization > scheme: $scheme; path: $path"
        output.writeString(scheme)
        output.writeString(path)
    }

    @Override
    Path  read(Kryo kryo, Input input, Class<Path> type) {
        final scheme = input.readString()
        final path = input.readString()
        log.trace "Path de-serialization > scheme: $scheme; path: $path"

        if( "file".equalsIgnoreCase(scheme) ) {
            return FileSystems.getDefault().getPath(path)
        }

        // try to find provider
        for (FileSystemProvider provider: FileSystemProvider.installedProviders()) {
            if (provider.getScheme().equalsIgnoreCase(scheme)) {
                return provider.getPath(new URI(path))
            }
        }

        throw new FileSystemNotFoundException("Provider \"$scheme\" not installed");

    }
}

/**
 * Serializer / de-serializer for Groovy GString
 */
@Slf4j
class GStringSerializer extends Serializer<GStringImpl> {

    @Override
    void write(Kryo kryo, Output stream, GStringImpl object) {
        kryo.writeObject( stream, object.getValues() )
        kryo.writeObject( stream, object.getStrings() )
    }

    @Override
    GStringImpl read(Kryo kryo, Input stream, Class<GStringImpl> type) {
        Object[] values = kryo.readObject(stream, Object[].class)
        String[] strings = kryo.readObject(stream, String[].class)
        return new GStringImpl(values, strings)
    }
}